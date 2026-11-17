package com.yusijia.houseclientservices.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证博查检索结果的解析、去重与压缩。
 * <p>
 * 夹具 {@code /bocha/websearch.json} 是一次真实调用的裁剪版（3 条结果，保留完整的 summary）。
 * 关注四件事：摘要必须被截断、来源和链接必须保留、重复内容要去掉、重排要能按阈值裁剪。
 */
class WebSearchFormatTest {

    private static String sample() throws IOException {
        try (InputStream in = WebSearchFormatTest.class.getResourceAsStream("/bocha/websearch.json")) {
            assertNotNull(in, "缺少测试夹具 /bocha/websearch.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String json(String... urlTitlePairs) {
        StringBuilder sb = new StringBuilder("{\"code\":200,\"data\":{\"webPages\":{\"value\":[");
        for (int i = 0; i < urlTitlePairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(urlTitlePairs[i]).append("\",")
                    .append("\"url\":\"").append(urlTitlePairs[i + 1]).append("\",")
                    .append("\"siteName\":\"测试站\",\"datePublished\":\"2026-01-05T10:00:00+08:00\",")
                    .append("\"summary\":\"摘要").append(i / 2).append("\"}");
        }
        return sb.append("]}}}").toString();
    }

    @Test
    void parsesSearchResults() throws Exception {
        List<WebSearchFormat.WebResult> results = WebSearchFormat.parseSearch(sample());

        assertEquals(3, results.size());
        WebSearchFormat.WebResult first = results.get(0);
        assertTrue(first.title().contains("南京首套房"));
        assertEquals("https://nj.loupan.com/html/news/202607/5603362.html", first.url());
        assertEquals("楼盘网", first.site());
        assertEquals("2026-07-22", first.date());
    }

    @Test
    void truncatesLongSummaryAndKeepsSource() throws Exception {
        List<WebSearchFormat.WebResult> results = WebSearchFormat.parseSearch(sample());
        String text = WebSearchFormat.format(results, 8);

        // 摘要截断到 300 字以内，避免几条结果吃掉上下文
        assertTrue(text.contains("…"));
        for (WebSearchFormat.WebResult result : results) {
            assertTrue(result.text().length() <= 301, "摘要未截断: " + result.text().length());
        }
        // 每条都要带来源和链接，否则模型会把自己的话当成搜索结果
        assertTrue(text.startsWith("[1] "));
        assertTrue(text.contains("链接：https://"));
        assertTrue(text.contains("（楼盘网，2026-07-22）"));
    }

    @Test
    void limitsResultCount() throws Exception {
        List<WebSearchFormat.WebResult> results = WebSearchFormat.parseSearch(sample());
        String text = WebSearchFormat.format(results, 2);
        assertTrue(text.contains("[2] "));
        assertFalse(text.contains("[3] "));
    }

    @Test
    void marksStaleResult() throws Exception {
        List<WebSearchFormat.WebResult> results = List.of(
                new WebSearchFormat.WebResult("旧校招", "https://a.example.com/1", "测试站",
                        LocalDate.now().minusYears(3).toString(), "简介"),
                new WebSearchFormat.WebResult("新校招", "https://a.example.com/2", "测试站",
                        LocalDate.now().minusDays(30).toString(), "简介"));

        String text = WebSearchFormat.format(results, 5);

        // 旧结果打标，模型才不会把三年前的校招当成在招岗位
        assertTrue(text.contains("信息较旧"));
        assertEquals(1, text.split("信息较旧", -1).length - 1);
    }

    @Test
    void dedupesSameArticleAcrossSubdomains() throws Exception {
        // 同一篇文章的移动端和 PC 端各返回一次
        List<WebSearchFormat.WebResult> results = WebSearchFormat.parseSearch(json(
                "2026南京公积金贷款利率", "https://m.nj.bendibao.com/live/178240.shtm",
                "2026南京公积金贷款利率", "https://www.nj.bendibao.com/live/178240.shtm",
                "2026年南京首套房贷款利率预测", "https://nj.loupan.com/html/news/202607/5590706.html"));

        assertEquals(2, results.size());
        assertEquals("https://m.nj.bendibao.com/live/178240.shtm", results.get(0).url());
    }

    @Test
    void dedupesSameArticleWithDifferentSectionSuffix() throws Exception {
        // 博查把同一篇校招稿按栏目名拆成"南京校园招聘""江苏校园招聘"两条
        List<WebSearchFormat.WebResult> results = WebSearchFormat.parseSearch(json(
                "江苏共创人造草坪有限公司2018校园招聘_南京校园招聘", "https://job.example.com/a/1",
                "江苏共创人造草坪有限公司2018校园招聘_江苏校园招聘", "https://job.example.com/a/2",
                "共创草坪 2025 校园招聘宣讲会", "https://job.example.com/a/3"));

        assertEquals(2, results.size());
        assertEquals("https://job.example.com/a/1", results.get(0).url());
    }

    @Test
    void dedupeAcrossBatchesKeepsOriginalOrder() throws Exception {
        List<WebSearchFormat.WebResult> batch1 = WebSearchFormat.parseSearch(json(
                "江苏共创人造草坪 2026 招聘", "https://job.example.com/a/1",
                "共创草坪 Java 开发岗", "https://job.example.com/a/2"));
        List<WebSearchFormat.WebResult> batch2 = WebSearchFormat.parseSearch(json(
                "江苏共创人造草坪 2026 招聘", "https://m.job.example.com/a/1",
                "共创草坪 外包 直签", "https://job.example.com/a/3"));

        List<WebSearchFormat.WebResult> merged = new java.util.ArrayList<>(batch1);
        merged.addAll(batch2);

        List<WebSearchFormat.WebResult> distinct = WebSearchFormat.dedupe(merged);

        assertEquals(3, distinct.size());
        assertEquals("https://job.example.com/a/1", distinct.get(0).url());
        assertEquals("https://job.example.com/a/2", distinct.get(1).url());
        assertEquals("https://job.example.com/a/3", distinct.get(2).url());
    }

    @Test
    void extractsReadableTextFromHtml() {
        String html = """
                <html><head><title>t</title><style>.a{color:red}</style>
                <script>var x = "<p>不该出现</p>";</script></head>
                <body>
                  <nav><a href="/">首页</a><a href="/x">教程</a></nav>
                  <article>
                    <h1>OpenCode 配置 Base URL 报 404</h1>
                    <p>把 baseURL 写成 http://localhost:11434/v1&nbsp;即可。</p>
                    <pre>{"model": "qwen/qwen3"}</pre>
                  </article>
                  <footer>版权所有</footer>
                </body></html>""";

        String text = WebSearchFormat.extractText(html);

        // 正文保留
        assertTrue(text.contains("OpenCode 配置 Base URL 报 404"));
        assertTrue(text.contains("http://localhost:11434/v1"));
        assertTrue(text.contains("qwen/qwen3"));
        // 脚本、样式、导航、版权短行都丢掉
        assertFalse(text.contains("不该出现"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("版权所有"));
        // 实体已还原
        assertFalse(text.contains("&nbsp;"));
    }

    @Test
    void extractsNothingFromBlankHtml() {
        assertEquals("", WebSearchFormat.extractText(null));
        assertEquals("", WebSearchFormat.extractText(""));
    }

    @Test
    void detectsChallengePage() {
        assertTrue(WebSearchFormat.looksLikeChallenge(
                "Security Verification 当前环境异常，请完成验证后继续访问"));
        assertTrue(WebSearchFormat.looksLikeChallenge("请完成验证后继续访问本站"));
        assertFalse(WebSearchFormat.looksLikeChallenge(
                "把 baseURL 改成 http://localhost:11434/v1 之后，404 消失，模型列表能正常返回。"));
        assertFalse(WebSearchFormat.looksLikeChallenge(""));
    }

    @Test
    void cleansFirecrawlMarkdown() {
        String markdown = """
                [![](https://img-home.csdnimg.cn/logo.png)](https://www.csdn.net/)

                登录

                # OpenCode 配置 Base URL 报 model_not_found

                ![封面](https://i-operation.csdnimg.cn/abc.webp)

                把 [opencode.json](https://x.example.com/a) 里的 baseURL 改成 `http://localhost:11434/v1`，
                然后执行 `curl http://localhost:11434/v1/models` 确认模型列表。

                - 登录
                - 注册

                ---
                """;

        String text = WebSearchFormat.cleanMarkdown(markdown);

        // 正文留下，链接只剩文字
        assertTrue(text.contains("OpenCode 配置 Base URL 报 model_not_found"));
        assertTrue(text.contains("opencode.json"));
        assertTrue(text.contains("curl http://localhost:11434/v1/models"));
        // 图片、链接目标、井号、分隔线、导航短行都清掉
        assertFalse(text.contains("csdnimg.cn"));
        assertFalse(text.contains("https://x.example.com/a"));
        assertFalse(text.contains("# OpenCode"));
        assertFalse(text.contains("---"));
        assertFalse(text.lines().anyMatch(line -> line.equals("登录")));
    }

    @Test
    void cleansNothingFromBlankMarkdown() {
        assertEquals("", WebSearchFormat.cleanMarkdown(null));
        assertEquals("", WebSearchFormat.cleanMarkdown("   "));
    }

    @Test
    void cutsSiteNavigationBeforeTitle() {
        String markdown = """
                登录后您可以：
                复制代码和一键运行
                与博主大V深度互动
                解锁海量精选资源
                获取前沿技术资讯
                CSDN - VIP
                # OpenCode 配置 Base URL 后报 model\\_not\\_found？用 opencode.json 修正模型 ID
                如果 OpenCode 已经能启动，自定义 Provider 也出现在列表里，先读取 /v1/models。
                """;

        String text = WebSearchFormat.cleanMarkdown(markdown,
                "OpenCode 配置 Base URL 后报 model_not_found？用 opencode.json 修正模型 ID - CSDN博客");

        // 标题之前的导航整段消失，标题和正文留下（下划线转义已还原）
        assertFalse(text.contains("登录后您可以"));
        assertFalse(text.contains("深度互动"));
        assertTrue(text.startsWith("OpenCode 配置 Base URL 后报 model_not_found"));
        assertTrue(text.contains("/v1/models"));
    }

    @Test
    void formatsBodyExcerptWhenAvailable() {
        List<WebSearchFormat.WebResult> results = List.of(
                new WebSearchFormat.WebResult("标题一", "https://a.example.com/1", "站", "2026-01-01", "摘要一"),
                new WebSearchFormat.WebResult("标题二", "https://a.example.com/2", "站", "2026-01-01", "摘要二"));

        String text = WebSearchFormat.format(results, 5,
                new java.util.HashMap<>(java.util.Map.of("https://a.example.com/1", "正文里的 baseURL 配置片段")));

        // 只有抓到的条目才带正文；没抓到的不出现空行
        assertTrue(text.contains("正文摘录：正文里的 baseURL 配置片段"));
        assertEquals(1, text.split("正文摘录：", -1).length - 1);
    }

    @Test
    void parsesRerankForBothStyles() throws Exception {
        String dashscope = "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.42},"
                + "{\"index\":2,\"relevance_score\":0.91},{\"index\":1,\"relevance_score\":0.08}]}}";
        List<WebSearchFormat.RerankHit> hits = WebSearchFormat.parseRerank(dashscope);
        assertEquals(List.of(2, 0, 1), hits.stream().map(WebSearchFormat.RerankHit::index).toList());
        assertEquals(List.of(2, 0, 1), WebSearchFormat.parseRerankOrder(dashscope));

        String openai = "{\"results\":[{\"index\":1,\"relevance_score\":0.8},"
                + "{\"index\":0,\"relevance_score\":0.2}]}";
        assertEquals(List.of(1, 0), WebSearchFormat.parseRerankOrder(openai));
    }

    @Test
    void dropsLowScoreHitsButKeepsAtLeastThree() throws Exception {
        List<WebSearchFormat.WebResult> results = List.of(
                new WebSearchFormat.WebResult("一", "https://a.example.com/1", "站", "", "1"),
                new WebSearchFormat.WebResult("二", "https://a.example.com/2", "站", "", "2"),
                new WebSearchFormat.WebResult("三", "https://a.example.com/3", "站", "", "3"),
                new WebSearchFormat.WebResult("四", "https://a.example.com/4", "站", "", "4"));

        // 阈值 0.2：只有 3 条及格，第 4 条被丢弃
        List<WebSearchFormat.WebResult> ranked = WebSearchFormat.applyOrder(results,
                List.of(new WebSearchFormat.RerankHit(2, 0.9),
                        new WebSearchFormat.RerankHit(0, 0.5),
                        new WebSearchFormat.RerankHit(1, 0.21),
                        new WebSearchFormat.RerankHit(3, 0.05)),
                0.2);
        assertEquals(List.of("三", "一", "二"), ranked.stream().map(WebSearchFormat.WebResult::title).toList());

        // 只有 1 条及格时补足到 3 条，避免模型手里没有依据
        List<WebSearchFormat.WebResult> padded = WebSearchFormat.applyOrder(results,
                List.of(new WebSearchFormat.RerankHit(3, 0.9),
                        new WebSearchFormat.RerankHit(1, 0.1),
                        new WebSearchFormat.RerankHit(2, 0.05)),
                0.5);
        assertEquals(3, padded.size());
        assertEquals("四", padded.get(0).title());

        // 全部低于阈值：保留原顺序，宁多不少
        assertEquals(results, WebSearchFormat.applyOrder(results,
                List.of(new WebSearchFormat.RerankHit(1, 0.1)), 0.5));
        assertEquals(results, WebSearchFormat.applyOrder(results, List.of(), 0.2));
        assertEquals(results, WebSearchFormat.applyOrder(results, null, 0.2));
    }

    @Test
    void normalizesUrlAndTitle() {
        assertEquals("nj.bendibao.com/live/178240.shtm",
                WebSearchFormat.normalizeUrl("https://m.nj.bendibao.com/live/178240.shtm?from=wx"));
        assertEquals("nj.bendibao.com/live/178240.shtm",
                WebSearchFormat.normalizeUrl("http://www.nj.bendibao.com/live/178240.shtm/"));
        assertEquals("江苏共创人造草坪有限公司2018校园招聘",
                WebSearchFormat.normalizeTitle("江苏共创人造草坪有限公司2018校园招聘_南京校园招聘"));
        assertEquals("2026南京公积金贷款利率是多少?",
                WebSearchFormat.normalizeTitle("2026南京公积金贷款利率是多少?- 南京本地宝"));
        // 太短的标题不截，避免误伤
        assertEquals("A_B", WebSearchFormat.normalizeTitle("A_B"));
    }

    @Test
    void reportsApiError() {
        Exception e = assertThrows(IllegalStateException.class,
                () -> WebSearchFormat.parseSearch("{\"code\":403,\"msg\":\"无权限\"}"));
        assertTrue(e.getMessage().contains("403"));
    }
}

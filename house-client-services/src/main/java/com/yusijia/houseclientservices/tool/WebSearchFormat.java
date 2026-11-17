package com.yusijia.houseclientservices.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联网检索结果的解析、去重与压缩。
 * <p>
 * 抽成纯静态方法是为了能在单测里直接喂真实响应 JSON 验证格式，
 * 不需要起 Spring、也不需要真的联网。
 * <p>
 * 四个约束：
 * 1. 原始响应里 summary 每条上千字，5 条就能吃掉整个上下文，必须截断；
 * 2. 输出必须带来源和链接，否则模型会把自己的话当成搜索结果；
 * 3. 同一篇内容常以 m.xxx / www.xxx 两个域名、或"南京版/江苏版"两个标题各返回一次，
 *    必须去重，否则重排后同源内容会占掉输出名额；
 * 4. 两年前的结果不打标，模型就会把 2018 年的校招当成在招岗位。
 */
public final class WebSearchFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条摘要截断长度 */
    private static final int SUMMARY_LIMIT = 300;

    /** 单条正文摘录截断长度：够模型读出配置片段，又不至于挤掉其它条 */
    private static final int BODY_LIMIT = 1200;

    /** 超过这个天数的结果视为"较旧"，格式化时打标（不丢弃，可能是唯一依据） */
    private static final long STALE_DAYS = 730;

    /** 反爬验证页的特征词（小写比较）。刻意不含单独的"验证码"：讲注册登录的文章开头也会出现 */
    private static final List<String> CHALLENGE_MARKERS = List.of(
            "security verification", "just a moment", "checking your browser",
            "enable javascript and cookies", "cf-browser-verification",
            "安全验证", "人机验证", "滑动验证", "请完成验证");

    /** 补足结果数：阈值过严时至少保留这么多条，避免模型无依据可写 */
    private static final int MIN_KEEP = 3;

    private WebSearchFormat() {
    }

    /**
     * 一条检索结果。
     */
    public record WebResult(String title, String url, String site, String date, String text) {
    }

    /**
     * 重排结果：原始下标 + 相关度分数。
     */
    public record RerankHit(int index, double score) {
    }

    /**
     * 解析博查 web-search 响应。code != 200 时抛异常，由调用方转成给模型的失败文案。
     */
    public static List<WebResult> parseSearch(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        int code = root.path("code").asInt(200);
        if (code != 200) {
            String msg = root.path("msg").asText(root.path("message").asText("未知错误"));
            throw new IllegalStateException("code=" + code + "，msg=" + msg);
        }
        JsonNode values = root.path("data").path("webPages").path("value");
        List<WebResult> results = new ArrayList<>();
        if (!values.isArray()) {
            return results;
        }
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (JsonNode item : values) {
            String url = item.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            String title = item.path("name").asText("").trim();
            // 同 URL 或同主标题都视为重复：博查会把一篇文章按不同子域名、不同栏目名各返回一次
            if (!seenUrls.add(normalizeUrl(url))) {
                continue;
            }
            String titleKey = normalizeTitle(title);
            if (!titleKey.isEmpty() && !seenTitles.add(titleKey)) {
                continue;
            }
            String text = item.path("summary").asText("");
            if (text.isBlank()) {
                text = item.path("snippet").asText("");
            }
            String date = item.path("datePublished").asText("");
            if (date.length() > 10) {
                date = date.substring(0, 10);
            }
            results.add(new WebResult(
                    title,
                    url,
                    item.path("siteName").asText("").trim(),
                    date,
                    clip(text.replaceAll("\\s+", " ").trim())));
        }
        return results;
    }

    /**
     * 跨批次去重：多 query 并发检索后，同一篇文章会从不同 query、不同子域名各回来一次。
     * 同 URL 或同主标题都视为重复，保留第一次出现的那条。
     */
    public static List<WebResult> dedupe(List<WebResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        List<WebResult> distinct = new ArrayList<>(results.size());
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (WebResult result : results) {
            if (!seenUrls.add(normalizeUrl(result.url()))) {
                continue;
            }
            String titleKey = normalizeTitle(result.title());
            if (!titleKey.isEmpty() && !seenTitles.add(titleKey)) {
                continue;
            }
            distinct.add(result);
        }
        return distinct;
    }

    /**
     * 解析重排结果，返回按下标去重的命中列表（分数高的在前）。
     * 兼容三种返回结构：DashScope（output.results）、OpenAI 兼容（results）、博查（data.results）。
     */
    public static List<RerankHit> parseRerank(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode items = root.path("output").path("results");
        if (!items.isArray() || items.isEmpty()) {
            items = root.path("results");
        }
        if (!items.isArray() || items.isEmpty()) {
            items = root.path("data").path("results");
        }
        List<RerankHit> hits = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (!item.has("index")) {
                    continue;
                }
                int index = item.path("index").asInt();
                if (seen.add(index)) {
                    hits.add(new RerankHit(index, item.path("relevance_score").asDouble(0)));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
        return hits;
    }

    /**
     * 只取下标顺序，便于测试与日志。
     */
    public static List<Integer> parseRerankOrder(String json) throws Exception {
        List<Integer> order = new ArrayList<>();
        for (RerankHit hit : parseRerank(json)) {
            order.add(hit.index());
        }
        return order;
    }

    /**
     * 按重排结果重排，并丢掉分数低于阈值的结果。
     * <p>
     * 不丢弃全部：全部低于阈值说明这条 query 只有弱相关内容，此时保留原顺序；
     * 高于阈值不足 {@link #MIN_KEEP} 条时按分数补足，避免模型手里没有依据。
     */
    public static List<WebResult> applyOrder(List<WebResult> results, List<RerankHit> hits, double minScore) {
        if (results == null || results.isEmpty() || hits == null || hits.isEmpty()) {
            return results;
        }
        List<WebResult> ranked = new ArrayList<>(results.size());
        for (RerankHit hit : hits) {
            if (hit.score() < minScore) {
                continue;
            }
            int index = hit.index();
            if (index >= 0 && index < results.size() && !ranked.contains(results.get(index))) {
                ranked.add(results.get(index));
            }
        }
        if (ranked.isEmpty()) {
            return results;
        }
        for (WebResult result : results) {
            if (ranked.size() >= MIN_KEEP) {
                break;
            }
            if (!ranked.contains(result)) {
                ranked.add(result);
            }
        }
        return ranked;
    }

    /**
     * 清理 Firecrawl 返回的 markdown，只留能当正文读的部分。
     * <p>
     * markdown 比原始 HTML 干净，但仍会带图片语法、链接目标、列表符号和导航文字，
     * 直接截断会把额度浪费在 {@code ![](https://i-operation.csdnimg.cn/...)} 这类东西上。
     */
    public static String cleanMarkdown(String markdown) {
        return cleanMarkdown(markdown, null);
    }

    /**
     * 清理 Firecrawl 返回的 markdown，只留能当正文读的部分。
     * <p>
     * markdown 比原始 HTML 干净，但仍会带图片语法、链接目标、列表符号和导航文字，
     * 直接截断会把额度浪费在 {@code ![](https://i-operation.csdnimg.cn/...)} 这类东西上。
     *
     * @param title 检索结果的标题。正文里通常也有这个标题，用它把前面的站点导航裁掉；
     *              传 null 则只按"短行"规则裁
     */
    public static String cleanMarkdown(String markdown, String title) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s{0,3}[-*+>|]+\\s*", "")
                .replaceAll("(?m)^\\s*\\d+[.)]\\s+", "");
        text = trimToTitle(text, title);

        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String line : text.split("\n")) {
            String trimmed = line.replaceAll("\\s+", " ").trim();
            // 正文开始前，短行基本是"登录/复制代码/会员礼包"这类导航，先跳过。
            // 正文里的短标题（如"一、问题现象"）在 started 之后不会被误删。
            if (!started && trimmed.length() < 30) {
                continue;
            }
            if (trimmed.length() < 6 || trimmed.chars().allMatch(c -> !Character.isLetterOrDigit(c))) {
                continue;
            }
            started = true;
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    /**
     * 从正文里找到文章标题，丢掉它前面的站点导航。
     * 比对时去掉反斜杠转义（CSDN 的 markdown 会把下划线写成 {@code \_}），并且只比标题前 24 字——
     * 检索结果的标题常带" - CSDN博客"这类站点后缀，全文比对必然失败。
     */
    private static String trimToTitle(String text, String title) {
        if (title == null || title.isBlank()) {
            return text;
        }
        String flatText = text.replace("\\", "");
        String flatTitle = title.replace("\\", "").trim();
        if (flatTitle.length() < 8) {
            return text;
        }
        String head = flatTitle.substring(0, Math.min(24, flatTitle.length()));
        int index = flatText.indexOf(head);
        return index > 0 ? flatText.substring(index) : text;
    }

    /**
     * 判断抓到的正文是不是反爬验证页。
     * <p>
     * 有些站点（如 CSDN 的部分线路）不返回 4xx，而是 200 + "Security Verification" 页面，
     * 内容全是"请完成验证"之类的提示。这种文本一旦当正文喂给模型，
     * 模型就会围绕"验证"编出一段无关回答，比没有正文更糟。
     */
    public static boolean looksLikeChallenge(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // 只扫开头：验证页的提示语都在最前面，后面可能跟着一堆无关内容
        String head = text.length() > 800 ? text.substring(0, 800) : text;
        String lower = head.toLowerCase();
        for (String marker : CHALLENGE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格式化给模型看的检索结果：标题、来源、时间、摘要、链接。
     * 超过两年的结果带"信息较旧"标记，让模型不会把旧招聘当成在招岗位。
     */
    public static String format(List<WebResult> results, int limit) {
        return format(results, limit, Map.of());
    }

    /**
     * 带正文摘录的格式化。正文比服务端摘要具体得多（配置片段、命令、错误栈都在正文里），
     * 但正文也带导航噪声，所以只在 top-N 上抓、抓完截断，见 {@link #extractText(String)}。
     */
    public static String format(List<WebResult> results, int limit, Map<String, String> bodies) {
        if (results == null || results.isEmpty()) {
            return "未检索到相关内容。";
        }
        int size = Math.min(limit, results.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            WebResult result = results.get(i);
            sb.append('[').append(i + 1).append("] ").append(result.title());
            List<String> meta = new ArrayList<>();
            if (!result.site().isBlank()) {
                meta.add(result.site());
            }
            if (!result.date().isBlank()) {
                meta.add(result.date());
                if (isStale(result.date())) {
                    meta.add("信息较旧");
                }
            }
            if (!meta.isEmpty()) {
                sb.append("（").append(String.join("，", meta)).append("）");
            }
            sb.append('\n');
            if (!result.text().isBlank()) {
                sb.append(result.text()).append('\n');
            }
            String body = bodies == null ? null : bodies.get(result.url());
            if (body != null && !body.isBlank()) {
                sb.append("正文摘录：").append(clip(body, BODY_LIMIT)).append('\n');
            }
            sb.append("链接：").append(result.url()).append('\n');
            if (i < size - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 网页正文抽取：去脚本/样式/注释，按块级标签断行，丢掉导航、按钮、版权这类短行。
     * <p>
     * 不用 jsoup 是为了不新增依赖；这里只需要"够模型读"的纯文本，不需要结构化 DOM。
     */
    public static String extractText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = text.replaceAll("(?i)</(p|div|br|li|tr|h[1-6]|section|article|pre)>", "\n");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        text = unescapeHtml(text);

        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\n")) {
            String trimmed = line.replaceAll("\\s+", " ").trim();
            // 导航栏、按钮、版权行都很短，保留它们只会挤掉正文
            if (trimmed.length() < 8) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    private static String unescapeHtml(String text) {
        String value = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        return value.replaceAll("&#(\\d{2,5});", "");
    }

    /**
     * URL 归一化：去协议、去 m./www. 前缀、去掉跟踪参数和末尾斜杠。
     * 只用于去重，不用于展示。
     */
    static String normalizeUrl(String url) {
        String value = url.trim().toLowerCase();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        if (value.startsWith("m.")) {
            value = value.substring(2);
        } else if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 标题归一化：去空白，截掉 CMS 常见的"主标题_栏目名""主标题 | 站点名""主标题 - 站点名"后缀。
     * 这样"…2018校园招聘_南京校园招聘"和"…2018校园招聘_江苏校园招聘"会被认成同一条。
     */
    static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String value = title.replaceAll("\\s+", "");
        for (String separator : new String[]{"_", "|", "－", "—", "-"}) {
            int index = value.indexOf(separator);
            if (index >= 4) {
                value = value.substring(0, index);
            }
        }
        return value;
    }

    /**
     * 日期是否超过两年。拿不到合法日期时按"不旧"处理，避免误标。
     */
    static boolean isStale(String date) {
        if (date == null || date.length() < 10) {
            return false;
        }
        try {
            LocalDate published = LocalDate.parse(date.substring(0, 10));
            return ChronoUnit.DAYS.between(published, LocalDate.now()) > STALE_DAYS;
        } catch (Exception e) {
            return false;
        }
    }

    private static String clip(String text) {
        return clip(text, SUMMARY_LIMIT);
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}

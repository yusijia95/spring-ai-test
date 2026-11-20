package com.yusijia.houseclientservices.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.yusijia.houseclientservices.agent.PiiMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 联网检索：博查 Web Search + 可选重排。
 * <p>
 * 为什么需要它：模型只会用训练数据回答，碰到"今年首套房贷利率""契税怎么收""XX 银行网点电话"
 * 这类时效性或外部事实，没有检索就只能编。这里是唯一的对外事实来源。
 * <p>
 * 两段式：
 * 1. 博查 web-search 取候选（默认 20 条，摘要已由服务端抽取，不必自己抓网页）；
 * 2. 可选重排（默认 DashScope qwen3-rerank），把最相关的排到前面，低于阈值的丢掉。
 * 重排是<b>可选</b>的：账号没开通或超时会自动降级为服务端原顺序，并在本进程内关闭重排，
 * 不会因为重排 403 让整个问答失败。
 * <p>
 * 候选池有上限（实测请求 10 条博查有时只回 5 条），所以一个问题往往需要模型换角度多次调用，
 * 这件事写在 skills/web-search/SKILL.md 里，而不是靠工具内部拼 query。
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 交给 Firecrawl 排除的样板区域；选择器不命中也不报错 */
    private static final List<String> BOILERPLATE_SELECTORS = List.of(
            "nav", "aside", "header", "footer",
            "#nav", "#aside", ".sidebar", ".toolbar",
            ".recommend-box", ".comment-box", ".article-info-box");

    private final RestClient restClient;
    /** 抓正文用的客户端：读超时比检索短，抓不到就跳过，不能拖住整轮问答 */
    private final RestClient pageClient;
    private final String apiKey;
    private final String searchUrl;
    private final int count;
    private final int topN;
    private final String freshness;
    private final boolean rerankEnabled;
    private final String rerankUrl;
    private final String rerankApiKey;
    private final String rerankModel;
    private final double rerankMinScore;
    private final boolean rerankDashScopeStyle;
    private final boolean bodyFetchEnabled;
    private final int bodyFetchCount;
    private final int bodyFetchMaxChars;
    private final RestClient scrapeClient;
    private final String firecrawlKey;
    private final String firecrawlUrl;
    private final int firecrawlMaxPages;
    private final int firecrawlTimeoutMs;
    private final int bodyTotalTimeoutMs;

    /** 重排一旦失败（403/404/超时）就在本进程内关掉，避免每次请求都白等一次 */
    private volatile boolean rerankAvailable = true;

    /** 正文本地缓存：同一篇文章会被不同问题反复命中，两天内不重复抓（Firecrawl 按页计费） */
    private final Map<String, CachedBody> bodyCache = new ConcurrentHashMap<>();

    /** 正文缓存条数上限。抓不到时也缓存（空串），但只用一小时的短 TTL */
    private static final int BODY_CACHE_LIMIT = 500;
    private static final long BODY_CACHE_TTL_MS = 2L * 24 * 3600 * 1000;
    private static final long BODY_CACHE_NEGATIVE_TTL_MS = 3600 * 1000L;

    private record CachedBody(String text, long expiresAt) {
    }

    /** 多 query 并发检索用的线程池；条数很少，固定 4 个线程足够 */
    private final ExecutorService retrievalPool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "web-search-pool");
        thread.setDaemon(true);
        return thread;
    });

    public WebSearchTools(@Value("${bocha.api-key:}") String apiKey,
                          @Value("${bocha.search-url:https://api.bochaai.com/v1/web-search}") String searchUrl,
                          @Value("${bocha.count:20}") int count,
                          @Value("${bocha.top-n:8}") int topN,
                          @Value("${bocha.freshness:threeYear}") String freshness,
                          @Value("${bocha.rerank.enabled:true}") boolean rerankEnabled,
                          @Value("${bocha.rerank.url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") String rerankUrl,
                          @Value("${bocha.rerank.api-key:${ALI_BAILIAN_KEY:}}") String rerankApiKey,
                          @Value("${bocha.rerank.model:qwen3-rerank}") String rerankModel,
                          @Value("${bocha.rerank.min-score:0.3}") double rerankMinScore,
                          @Value("${bocha.rerank.style:dashscope}") String rerankStyle,
                          @Value("${bocha.body-fetch.enabled:true}") boolean bodyFetchEnabled,
                          @Value("${bocha.body-fetch.count:3}") int bodyFetchCount,
                          @Value("${bocha.body-fetch.max-chars:1200}") int bodyFetchMaxChars,
                          @Value("${bocha.body-fetch.firecrawl.enabled:true}") boolean firecrawlEnabled,
                          @Value("${bocha.body-fetch.firecrawl.api-key:${FIRECRAWL_KEY:}}") String firecrawlKey,
                          @Value("${bocha.body-fetch.firecrawl.url:https://api.firecrawl.dev/v2/scrape}") String firecrawlUrl,
                          @Value("${bocha.body-fetch.firecrawl.max-pages:2}") int firecrawlMaxPages,
                          @Value("${bocha.body-fetch.firecrawl.timeout-ms:20000}") int firecrawlTimeoutMs,
                          @Value("${bocha.body-fetch.total-timeout-ms:15000}") int bodyTotalTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();

        SimpleClientHttpRequestFactory pageFactory = new SimpleClientHttpRequestFactory();
        pageFactory.setConnectTimeout(3_000);
        pageFactory.setReadTimeout(6_000);
        this.pageClient = RestClient.builder().requestFactory(pageFactory).build();

        this.apiKey = apiKey;
        this.searchUrl = searchUrl;
        this.count = count;
        this.topN = topN;
        // 注解默认值见 application.properties；这里只兜底空串，两处默认值保持一致
        this.freshness = (freshness == null || freshness.isBlank()) ? "threeYear" : freshness.trim();
        this.rerankEnabled = rerankEnabled;
        this.rerankUrl = rerankUrl;
        this.rerankApiKey = rerankApiKey;
        this.rerankModel = rerankModel;
        this.rerankMinScore = rerankMinScore;
        this.rerankDashScopeStyle = !"openai".equalsIgnoreCase(rerankStyle);
        this.bodyFetchEnabled = bodyFetchEnabled;
        this.bodyFetchCount = Math.max(0, bodyFetchCount);
        this.bodyFetchMaxChars = Math.max(0, bodyFetchMaxChars);

        SimpleClientHttpRequestFactory scrapeFactory = new SimpleClientHttpRequestFactory();
        scrapeFactory.setConnectTimeout(5_000);
        // Firecrawl 要起无头浏览器，比直连慢得多：实测单页 12 秒，这里给足
        scrapeFactory.setReadTimeout(firecrawlTimeoutMs + 5_000);
        this.scrapeClient = RestClient.builder().requestFactory(scrapeFactory).build();
        this.firecrawlKey = firecrawlKey;
        this.firecrawlUrl = firecrawlUrl;
        this.firecrawlMaxPages = firecrawlEnabled ? Math.max(0, firecrawlMaxPages) : 0;
        this.firecrawlTimeoutMs = Math.max(1_000, firecrawlTimeoutMs);
        this.bodyTotalTimeoutMs = Math.max(1_000, bodyTotalTimeoutMs);
        if (this.firecrawlMaxPages > 0 && (firecrawlKey == null || firecrawlKey.isBlank())) {
            log.info("未配置 FIRECRAWL_KEY，正文兜底走 keyless 档（按 IP 限流，建议配 key）");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 bocha.api-key（环境变量 BOCHA_KEY），webSearch 工具将不可用");
        }
    }

    /**
     * 联网搜索公开信息。返回若干条带标题、来源、时间和链接的摘要，可直接作为回答依据。
     */
    @Tool(name = "webSearch", description = """
            联网搜索公开信息。适用于：政策法规、房贷利率、税费、购房资格、新闻、
            机构地址与电话、公司/岗位/就业等公开信息、以及业务系统里查不到的通用常识。
            不适用于：本系统已有的楼盘、房价、房屋、认购单、周边配套（这些必须用业务工具查询）。
            入参 query 用中文关键词，一次只查一个主题；问的是公司或岗位时用"公司名 + 岗位/招聘/外包"这类具体词，
            不要直接把客户原话整句塞进来。
            一次结果可能覆盖不全，信息不足时换不同角度再调用一次（最多 3 次）。
            返回若干条结果，每条含标题、来源、日期、摘要和链接；标注"信息较旧"的日期超过两年。""")
    public String webSearch(String query) {
        if (query == null || query.isBlank()) {
            return "请提供要检索的问题。";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "联网检索未配置，无法查询外部信息。请如实告知客户暂无法查询，不要凭记忆作答。";
        }
        String trimmed = query.trim();
        List<WebSearchFormat.WebResult> results;
        try {
            results = WebSearchFormat.parseSearch(search(trimmed));
        } catch (Exception e) {
            log.warn("联网检索失败: query={}, error={}", PiiMasker.mask(trimmed), e.getMessage());
            return "联网检索失败（" + shortMessage(e) + "）。请如实告知客户暂时查不到，不要凭记忆作答。";
        }
        if (results.isEmpty()) {
            return "未检索到相关内容。";
        }

        List<WebSearchFormat.RerankHit> hits = rerank(trimmed, results);
        List<WebSearchFormat.WebResult> ranked = WebSearchFormat.applyOrder(results, hits, rerankMinScore);
        String text = WebSearchFormat.format(ranked, topN);
        log.info("联网检索: query={}, 候选={}, 重排={}, 输出={}条",
                PiiMasker.mask(trimmed), results.size(), hits == null ? "未启用" : "已启用",
                Math.min(topN, ranked.size()));
        return text;
    }

    /**
     * 多 query 并发检索：query 由检索规划器拆好，这里只负责执行、合并、去重、重排。
     * <p>
     * 与 {@link #webSearch(String)} 的分工：这个方法是"系统主导"的路径，回答问题那一轮不再给模型 webSearch 工具，
     * 模型没有"要不要再查"的决定权；{@code webSearch} 保留为降级路径，规划失败时照旧由模型自己查。
     */
    public String searchMany(String question, List<String> queries) {
        if (apiKey == null || apiKey.isBlank()) {
            return "联网检索未配置，无法查询外部信息。请如实告知客户暂无法查询，不要凭记忆作答。";
        }
        String anchor = question == null || question.isBlank() ? "" : question.trim();
        List<String> effective = (queries == null || queries.isEmpty())
                ? (anchor.isEmpty() ? List.of() : List.of(anchor))
                : queries;
        if (effective.isEmpty()) {
            return "未检索到相关内容。";
        }
        return formatResults(anchor, retrieve(effective));
    }

    /**
     * 只负责执行与合并：并发抓取、跨批去重，<b>不做重排和排版</b>。
     * <p>
     * 拆成两级是为了多轮补查（方案 2）：第二轮的结果必须和第一轮一起重排，
     * 各排各的会让两轮的分数不可比、重复项也去不掉。
     *
     * @return 去重后的候选，失败或无结果时返回空列表
     */
    public List<WebSearchFormat.WebResult> retrieve(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        List<Future<List<WebSearchFormat.WebResult>>> futures = new ArrayList<>(queries.size());
        for (String query : queries) {
            futures.add(retrievalPool.submit(() -> {
                try {
                    return WebSearchFormat.parseSearch(search(query));
                } catch (Exception e) {
                    log.warn("联网检索失败: query={}, error={}", query, shortMessage(e));
                    return List.<WebSearchFormat.WebResult>of();
                }
            }));
        }

        List<WebSearchFormat.WebResult> merged = new ArrayList<>();
        int succeeded = 0;
        for (Future<List<WebSearchFormat.WebResult>> future : futures) {
            try {
                List<WebSearchFormat.WebResult> part = future.get(30, TimeUnit.SECONDS);
                if (!part.isEmpty()) {
                    succeeded++;
                }
                merged.addAll(part);
            } catch (Exception e) {
                log.warn("并发检索等待失败: {}", shortMessage(e));
            }
        }
        List<WebSearchFormat.WebResult> distinct = WebSearchFormat.dedupe(merged);
        log.info("联网检索(抓取): queries={}, 成功={}, 候选={}, 去重后={}",
                queries, succeeded, merged.size(), distinct.size());
        return distinct;
    }

    /**
     * 对合并后的候选做重排、阈值裁剪和排版。多轮补查时只在最后调用一次。
     *
     * @param question 重排用的锚点，应当是客户的原始问题而不是某条子 query
     */
    public String formatResults(String question, List<WebSearchFormat.WebResult> results) {
        if (results == null || results.isEmpty()) {
            return "未检索到相关内容。";
        }
        String anchor = question == null || question.isBlank() ? results.get(0).title() : question.trim();
        List<WebSearchFormat.RerankHit> hits = rerank(anchor, results);
        List<WebSearchFormat.WebResult> ranked = WebSearchFormat.applyOrder(results, hits, rerankMinScore);
        Map<String, String> bodies = fetchBodies(ranked);
        log.info("联网检索(成稿): 候选={}, 重排={}, 正文={}条, 输出={}条",
                results.size(), hits == null ? "未启用" : "已启用", bodies.size(),
                Math.min(topN, ranked.size()));
        return WebSearchFormat.format(ranked, topN, bodies);
    }

    /**
     * 抓 top-N 的网页正文。摘要只有 300 字，配置片段、命令、错误栈往往只在正文里。
     * 抓不到不影响回答：失败的条目直接没有"正文摘录"这一行。
     */
    private Map<String, String> fetchBodies(List<WebSearchFormat.WebResult> ranked) {
        if (!bodyFetchEnabled || bodyFetchCount <= 0 || ranked.isEmpty()) {
            return Map.of();
        }
        List<WebSearchFormat.WebResult> targets = ranked.subList(0, Math.min(bodyFetchCount, ranked.size()));
        Map<String, String> bodies = new HashMap<>();
        // 整段抓正文的总预算：直连 8 秒 + Firecrawl 20 秒叠加起来会到 28 秒，
        // 客户等不起。超预算时用已经抓到的部分，剩下的这轮就没有正文。
        long deadline = System.currentTimeMillis() + bodyTotalTimeoutMs;
        int cached = 0;
        int direct = 0;

        // 0 段：本地缓存。同一篇文章常被不同问题引用（"OpenCode 404" 和 "opencode baseURL" 会命中同一篇），
        // 两天内不重复抓——Firecrawl 按页计费，直连也要几百毫秒
        List<WebSearchFormat.WebResult> pending = new ArrayList<>();
        for (WebSearchFormat.WebResult target : targets) {
            String hit = cachedBody(target.url());
            if (hit == null) {
                pending.add(target);
            } else if (hit.isBlank()) {
                cached++;   // 已知抓不到（404/验证页），这轮直接跳过
            } else {
                bodies.put(target.url(), hit);
                cached++;
            }
        }

        // 第一段：直连。免费、几百毫秒，大多数站点够用
        List<WebSearchFormat.WebResult> failed = new ArrayList<>();
        List<Future<String>> futures = pending.stream()
                .map(result -> retrievalPool.submit(() -> fetchBody(result.url())))
                .toList();
        for (int i = 0; i < pending.size(); i++) {
            String url = pending.get(i).url();
            try {
                long wait = Math.max(500, Math.min(8_000, deadline - System.currentTimeMillis()));
                String body = futures.get(i).get(wait, TimeUnit.MILLISECONDS);
                if (body != null && !body.isBlank()) {
                    bodies.put(url, body);
                    rememberBody(url, body);
                    direct++;
                    continue;
                }
            } catch (Exception e) {
                log.debug("正文抓取等待失败: {}", shortMessage(e));
            }
            failed.add(pending.get(i));
        }

        // 第二段：只对直连失败的走 Firecrawl。CSDN 这类 WAF 站点直连 521，
        // Firecrawl 用无头浏览器能抓下来（实测同一篇文章 200 + 5 万字正文）。
        // 不整体替换成 Firecrawl 是因为它单页要十几秒，而多数站点直连就够。
        int rescued = escalateBodies(failed, bodies, Math.min(firecrawlMaxPages, bodyFetchCount - bodies.size()),
                deadline);
        // 两段都没抓到的记成"抓不到"，一小时内不再为它白等
        for (WebSearchFormat.WebResult result : failed) {
            if (!bodies.containsKey(result.url())) {
                rememberBlocked(result.url());
            }
        }
        log.info("正文抓取: 目标={}, 命中缓存={}, 直连成功={}, Firecrawl 兜底成功={}",
                targets.size(), cached, direct, rescued);
        return bodies;
    }

    /**
     * 对直连失败的条目并发走 Firecrawl，成功就写进 bodies。
     *
     * @return 兜底救回来的条数
     */
    private int escalateBodies(List<WebSearchFormat.WebResult> failed, Map<String, String> bodies, int budget,
                               long deadline) {
        if (budget <= 0 || failed.isEmpty()) {
            return 0;
        }
        List<WebSearchFormat.WebResult> escalations = failed.subList(0, Math.min(budget, failed.size()));
        List<Future<String>> futures = escalations.stream()
                .map(result -> retrievalPool.submit(() -> fetchBodyViaFirecrawl(result)))
                .toList();

        int rescued = 0;
        for (int i = 0; i < escalations.size(); i++) {
            String url = escalations.get(i).url();
            try {
                long wait = Math.min(firecrawlTimeoutMs, deadline - System.currentTimeMillis());
                if (wait <= 0) {
                    log.debug("正文抓取预算用尽，Firecrawl 兜底跳过: url={}", url);
                    continue;
                }
                String body = futures.get(i).get(wait, TimeUnit.MILLISECONDS);
                if (body != null && !body.isBlank()) {
                    bodies.put(url, body);
                    // 兜底抓回来的也要进本地缓存：Firecrawl 自己也会缓存，
                    // 但走到它那里至少一趟 HTTP + 反序列化，本地命中是 0 成本
                    rememberBody(url, body);
                    rescued++;
                }
            } catch (Exception e) {
                log.debug("Firecrawl 兜底失败: url={}, error={}", url, shortMessage(e));
            }
        }
        return rescued;
    }

    /**
     * 用 Firecrawl 抓正文。它自带无头浏览器和代理池，能过掉直连过不去的反爬，
     * 代价是单页十几秒且按页计费（1 credit/页，免费档 1000 页/月）。
     */
    private String fetchBodyViaFirecrawl(WebSearchFormat.WebResult result) {
        String url = result == null ? null : result.url();
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("url", url);
            body.putArray("formats").add("markdown");
            body.put("onlyMainContent", true);
            body.put("timeout", firecrawlTimeoutMs);
            // 两天内的缓存直接复用：同一篇文章在多个问题里被引用时不重复花钱
            body.put("maxAge", 172_800_000L);
            // 站点侧栏/评论/工具栏不参与渲染选择器匹配也无害，命中就少几百字噪声
            ArrayNode exclude = body.putArray("excludeTags");
            for (String selector : BOILERPLATE_SELECTORS) {
                exclude.add(selector);
            }

            RestClient.RequestBodySpec spec = scrapeClient.post()
                    .uri(firecrawlUrl)
                    .contentType(MediaType.APPLICATION_JSON);
            if (firecrawlKey != null && !firecrawlKey.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + firecrawlKey);
            }
            String json = spec.body(body.toString()).retrieve().body(String.class);

            JsonNode root = MAPPER.readTree(json);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode data = root.path("data");
            int status = data.path("metadata").path("statusCode").asInt(200);
            if (status >= 400) {
                // 目标站点本身报错时 Firecrawl 也会返回文档（且计费），这种情况不要
                log.debug("Firecrawl 抓到的页面状态异常，已跳过: url={}, status={}", url, status);
                return null;
            }
            String text = WebSearchFormat.cleanMarkdown(data.path("markdown").asText(""), result.title());
            if (text.isBlank() || WebSearchFormat.looksLikeChallenge(text)) {
                return null;
            }
            return text.length() <= bodyFetchMaxChars ? text : text.substring(0, bodyFetchMaxChars);
        } catch (Exception e) {
            log.debug("Firecrawl 抓取失败: url={}, error={}", url, shortMessage(e));
            return null;
        }
    }

    private String fetchBody(String url) {
        if (url == null || !url.startsWith("http")) {
            return null;
        }
        try {
            String html = pageClient.get()
                    .uri(url)
                    // 少一个头就更容易吃到反爬验证页：Accept/Accept-Language 是普通浏览器都会带的
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", origin(url))
                    .retrieve()
                    .body(String.class);
            String text = WebSearchFormat.extractText(html);
            if (text.isBlank()) {
                return null;
            }
            // 200 也可能返回验证页（CSDN 部分线路如此），不能把验证页当正文喂给模型
            if (WebSearchFormat.looksLikeChallenge(text)) {
                log.debug("正文是验证页，已跳过: url={}", url);
                return null;
            }
            return text.length() <= bodyFetchMaxChars ? text : text.substring(0, bodyFetchMaxChars);
        } catch (HttpStatusCodeException e) {
            // 只记状态码：521 这类反爬响应体里是整段 base64 图片，打进日志会把日志撑爆
            log.debug("正文抓取失败: url={}, status={}", url, e.getStatusCode().value());
            return null;
        } catch (Exception e) {
            log.debug("正文抓取失败: url={}, error={}", url, shortMessage(e));
            return null;
        }
    }

    /** 抓正文时补一个同站 Referer，直连文章页看起来更像正常跳转 */
    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return url;
        }
    }

    @PreDestroy
    void shutdown() {
        retrievalPool.shutdownNow();
    }

    /** 取缓存；返回 null 表示没缓存（未抓过/已过期），空串表示已知抓不到 */
    private String cachedBody(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        CachedBody hit = bodyCache.get(url);
        if (hit == null) {
            return null;
        }
        if (hit.expiresAt() < System.currentTimeMillis()) {
            bodyCache.remove(url);
            return null;
        }
        return hit.text();
    }

    private void rememberBody(String url, String text) {
        if (url == null || text == null || text.isBlank()) {
            return;
        }
        if (bodyCache.size() >= BODY_CACHE_LIMIT) {
            // 条数很少（一天几十条），直接整体清空比实现 LRU 简单得多
            bodyCache.clear();
        }
        bodyCache.put(url, new CachedBody(text, System.currentTimeMillis() + BODY_CACHE_TTL_MS));
    }

    private void rememberBlocked(String url) {
        if (url == null || url.isBlank() || bodyCache.size() >= BODY_CACHE_LIMIT) {
            return;
        }
        bodyCache.put(url, new CachedBody("", System.currentTimeMillis() + BODY_CACHE_NEGATIVE_TTL_MS));
    }

    private String search(String query) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.put("count", count);
        body.put("summary", true);
        body.put("freshness", freshness);
        try {
            return postSearch(body);
        } catch (HttpClientErrorException e) {
            // 并发 3 条 query 时容易撞上博查的 QPS 限制（429），退一步重试一次
            if (e.getStatusCode().value() != 429) {
                throw e;
            }
            log.debug("联网检索被限流，800ms 后重试: query={}", query);
            sleepQuietly(800);
            return postSearch(body);
        }
    }

    private String postSearch(ObjectNode body) {
        return restClient.post()
                .uri(searchUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 可选重排。任何失败都返回 null（保留原顺序），不向上抛。
     * <p>
     * top_n 取候选总数而不是输出条数：只要前 N 条的分数，后面那些就没法按阈值判断该不该丢。
     */
    private List<WebSearchFormat.RerankHit> rerank(String query, List<WebSearchFormat.WebResult> results) {
        if (!rerankEnabled || !rerankAvailable || results.size() < 2
                || rerankApiKey == null || rerankApiKey.isBlank()) {
            return null;
        }
        try {
            ArrayNode documents = MAPPER.createArrayNode();
            for (WebSearchFormat.WebResult result : results) {
                documents.add(result.title() + "。" + result.text());
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", rerankModel);
            if (rerankDashScopeStyle) {
                ObjectNode input = body.putObject("input");
                input.put("query", query);
                input.set("documents", documents);
                ObjectNode parameters = body.putObject("parameters");
                parameters.put("return_documents", false);
                parameters.put("top_n", documents.size());
            } else {
                body.put("query", query);
                body.set("documents", documents);
                body.put("top_n", documents.size());
            }
            String json = restClient.post()
                    .uri(rerankUrl)
                    .header("Authorization", "Bearer " + rerankApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            List<WebSearchFormat.RerankHit> hits = WebSearchFormat.parseRerank(json);
            return hits.isEmpty() ? null : hits;
        } catch (Exception e) {
            rerankAvailable = false;
            log.warn("重排不可用，已降级为检索原顺序（本进程内不再尝试）: {}", shortMessage(e));
            return null;
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int index = message.indexOf('\n');
        String value = index > 0 ? message.substring(0, index) : message;
        // 有些异常（如 521 反爬响应）会把整个响应体塞进 message，必须再截一刀
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}

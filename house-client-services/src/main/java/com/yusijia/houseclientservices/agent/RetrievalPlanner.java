package com.yusijia.houseclientservices.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索规划器：把客户一句话拆成若干条检索词，交给代码并发执行。
 * <p>
 * 为什么需要它：只有一条 query 时，模型查到几条就认为够了，不会再查；
 * 而一个问题往往包含多个子问题（"是不是外包""岗位做什么""待遇如何"）。
 * 把"查几次"的决定权放在检索之前、交给代码，覆盖才有下界。
 * <p>
 * 与 {@link SkillSelector} 同构：一次短 LLM 调用、输出结构化结果、失败即降级。
 * 降级策略是返回空列表，调用方退化为"用原问题查一次"，也就是改造前的行为。
 */
@Component
public class RetrievalPlanner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单条 query 的长度上限，防止模型把整段问题连解释一起塞进来 */
    private static final int QUERY_MAX_LENGTH = 60;

    private static final String SYSTEM_PROMPT = """
            你是检索规划器。把客户的问题拆成多条中文检索词，交给搜索引擎。

            规则：
            - 输出 2 到 {maxQueries} 条，覆盖问题里的每个子问题（例如"是不是外包""岗位职责""薪资待遇"）。
            - 问题里的专有名词一个都不能丢：产品名、工具名、错误码、配置项名。
              例："OpenCode 连本地 Qwen 报 404" →
              "OpenCode 本地 Qwen 404"、"OpenCode 配置 baseURL /v1"、"ollama OpenAI 兼容接口 404"。
            - 排障类问题至少有一条要带具体配置项或路径（baseURL、/v1、opencode.json、端口），
              只查"xx 报错怎么解决"会召回大量水文。
            - 每条只查一个角度，写成"主体 + 限定词"，不要写成疑问句。
            - 不要照抄客户原话整句，不要加引号、序号、解释。
            - 客户问的是本系统业务（楼盘、房价、认购、周边、路线）时，输出空数组 []。
            - 只输出 JSON：{"queries":["...","..."]}
            """;

    private final ChatClient plannerChatClient;
    private final int maxQueries;

    /** 同一句话重复问（重试、刷新）时不必重新规划 */
    private final Map<String, List<String>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 500;
                }
            });

    public RetrievalPlanner(@Qualifier("skillSelectorChatClient") ChatClient plannerChatClient,
                            @Value("${agent.retrieval.max-queries:3}") int maxQueries) {
        this.plannerChatClient = plannerChatClient;
        this.maxQueries = Math.max(1, maxQueries);
    }

    /**
     * 规划检索词。任何异常都返回空列表，由调用方降级为单次检索。
     */
    public List<String> plan(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String key = question.trim().toLowerCase(Locale.ROOT);
        List<String> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        List<String> queries = List.of();
        try {
            String raw = plannerChatClient.prompt()
                    .system(SYSTEM_PROMPT.replace("{maxQueries}", String.valueOf(maxQueries)))
                    .user(question)
                    .call()
                    .content();
            queries = parseQueries(raw, maxQueries);
            log.debug("检索规划: question={}, queries={}", PiiMasker.mask(question), queries);
        } catch (Exception e) {
            log.warn("检索规划失败，降级为单次检索: {}", e.getMessage());
        }
        cache.put(key, queries);
        return queries;
    }

    /**
     * 解析规划器输出。容忍代码块包裹、多余前后缀、以及 {"queries":[...]} 与 [...] 两种结构。
     * 返回空列表表示"没规划出可用 query"，由调用方决定降级。
     */
    static List<String> parseQueries(String raw, int maxQueries) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        try {
            JsonNode array = MAPPER.readTree(text.substring(start, end + 1));
            if (!array.isArray()) {
                return result;
            }
            for (JsonNode node : array) {
                String query = sanitize(node.asText(""));
                if (query.isEmpty() || containsIgnoreCase(result, query)) {
                    continue;
                }
                result.add(query);
                if (result.size() >= maxQueries) {
                    break;
                }
            }
        } catch (Exception e) {
            return result;
        }
        return result;
    }

    /** 去掉模型爱加的序号、项目符号、引号和换行 */
    private static String sanitize(String raw) {
        String value = raw.trim()
                .replaceAll("^[\\s\\-*•·]+", "")
                .replaceAll("^\\d+[.、)）]\\s*", "")
                .replaceAll("^[\"'“”「」]+|[\"'“”「」]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.length() > QUERY_MAX_LENGTH) {
            value = value.substring(0, QUERY_MAX_LENGTH).trim();
        }
        return value;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String item : list) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

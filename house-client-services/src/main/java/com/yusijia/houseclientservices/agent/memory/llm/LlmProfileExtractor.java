package com.yusijia.houseclientservices.agent.memory.llm;

import com.yusijia.houseclientservices.agent.memory.ProfileExtractor;
import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用便宜模型从对话中提取长期偏好，写入 L3 用户画像。
 * <p>
 * 复用压缩器的 ChatClient（同一个便宜模型），不额外配模型。
 * 提示词要求模型输出 JSON 数组，解析失败时静默跳过。
 */
public class LlmProfileExtractor implements ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmProfileExtractor.class);

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "budget", "districtPref", "roomPref", "purpose", "other");

    private static final String SYSTEM = """
            你是客服对话的画像提取器。从给定的对话中识别客户<b>明确说过</b>的长期偏好。

            只提取以下字段（客户必须明确表达，禁止推测）：
            - budget：客户预算（如"300万左右"）
            - districtPref：意向区域（如"江宁区，靠近地铁"）
            - roomPref：户型偏好（如"三室两厅"）
            - purpose：购房目的（如"学区""自住""投资"）
            - other：其他长期偏好

            严格规则：
            - 只提取客户（"客户："行）明确说过的内容，客服说的不算
            - 如果客户没有明确提到某个字段，不要输出该字段
            - 值尽量简短（20 字以内），保留客户原话的关键信息
            - 不要提取当前在谈的具体楼盘、房号、认购单号（那是会话状态，不是长期偏好）

            输出格式（纯 JSON 数组，不要 Markdown 代码块）：
            [{"key":"budget","value":"300万左右"},{"key":"districtPref","value":"江宁区，靠近地铁"}]
            如果没有可提取的偏好，输出：[]
            """;

    private final ChatClient chatClient;
    private final UserProfileStore profileStore;
    private final String model;

    public LlmProfileExtractor(ChatClient chatClient, UserProfileStore profileStore, String model) {
        this.chatClient = chatClient;
        this.profileStore = profileStore;
        this.model = model;
    }

    @Override
    public Map<String, String> extract(String userId, List<Message> messages) {
        if (userId == null || userId.isBlank() || messages == null || messages.isEmpty()) {
            return Map.of();
        }
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM)
                    .user(buildInput(messages))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                return Map.of();
            }
            Map<String, String> extracted = parseJson(answer);
            if (extracted.isEmpty()) {
                return Map.of();
            }
            // 写入画像（store 内部会做 PII 脱敏 + 截断）
            for (Map.Entry<String, String> entry : extracted.entrySet()) {
                profileStore.upsert(userId, entry.getKey(), entry.getValue(), "对话自动提取");
            }
            log.info("画像自动提取: userId={}, fields={}", userId, extracted.keySet());
            return extracted;
        } catch (RuntimeException e) {
            log.warn("画像提取失败（model={}），跳过: {}", model, e.getMessage());
            return Map.of();
        }
    }

    private String buildInput(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            String text = message.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            sb.append(message.getMessageType() == MessageType.USER ? "客户：" : "客服：")
                    .append(text.trim())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析模型输出的 JSON 数组。
     * 容忍：前后有多余文字、Markdown 代码块包裹、单引号等。
     */
    static Map<String, String> parseJson(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        // 提取 JSON 数组部分
        String json = raw.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        json = json.substring(start, end + 1);
        // 手动解析简单的 [{"key":"...","value":"..."}] 格式
        // 不用 Jackson：模型输出可能不严格，手动解析更容错
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0 || objStart > end) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = json.substring(objStart + 1, objEnd);
            String key = extractField(obj, "key");
            String value = extractField(obj, "value");
            if (key != null && ALLOWED_KEYS.contains(key) && value != null && !value.isBlank()) {
                result.put(key, value.trim());
            }
            i = objEnd + 1;
        }
        return result;
    }

    private static String extractField(String obj, String fieldName) {
        // 匹配 "fieldName":"value" 或 'fieldName':'value'
        String pattern = "\"" + fieldName + "\"";
        int idx = obj.indexOf(pattern);
        if (idx < 0) {
            pattern = "'" + fieldName + "'";
            idx = obj.indexOf(pattern);
        }
        if (idx < 0) {
            return null;
        }
        int colonIdx = obj.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) {
            return null;
        }
        int valStart = colonIdx + 1;
        // 跳过空格
        while (valStart < obj.length() && obj.charAt(valStart) == ' ') {
            valStart++;
        }
        if (valStart >= obj.length()) {
            return null;
        }
        char quote = obj.charAt(valStart);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int valEnd = obj.indexOf(quote, valStart + 1);
        if (valEnd < 0) {
            return null;
        }
        return obj.substring(valStart + 1, valEnd);
    }
}

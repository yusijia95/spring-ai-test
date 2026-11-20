package com.yusijia.houseclientservices.agent.memory.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

/**
 * Message ↔ Redis 字符串。
 * <p>
 * 不用 JDK 序列化：Message 是实现类多态的接口，类结构随 Spring AI 版本变动，
 * 序列化内容一旦升级就解不开（Redis 里还有旧数据时直接抛异常）。
 * 这里只存"角色 + 文本"，升级不会炸；拿不到的信息（工具调用参数）本来也不需要回放。
 * <p>
 * 工具消息不进记忆（{@code MessageChatMemoryAdvisor} 只写用户消息和最终回答），
 * 所以不用考虑 assistant tool_calls 与 tool 消息的配对约束。
 */
public class ChatMessageCodec {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 落 Redis 的结构，字段名刻意做短：一条消息多存几次就是几百字节 */
    record Stored(String t, String c) {
    }

    /**
     * @return null 表示这条消息不值得存（空文本 / 未知类型），调用方跳过
     */
    public String encode(Message message) {
        if (message == null) {
            return null;
        }
        String text = message.getText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String type = switch (message.getMessageType()) {
            case USER -> "u";
            case ASSISTANT -> "a";
            case SYSTEM -> "s";
            case TOOL -> "t";
            default -> null;
        };
        if (type == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(new Stored(type, text));
        } catch (Exception e) {
            log.warn("记忆编码失败，跳过该条: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @return null 表示这条落库内容无法解析（旧版本残留 / 手写脏数据），调用方跳过
     */
    public Message decode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            Stored stored = MAPPER.readValue(raw, Stored.class);
            if (stored == null || !StringUtils.hasText(stored.c())) {
                return null;
            }
            return switch (stored.t() == null ? "" : stored.t()) {
                case "u" -> new UserMessage(stored.c());
                case "a" -> new AssistantMessage(stored.c());
                case "s" -> new SystemMessage(stored.c());
                // 工具消息没有 tool_call_id，回放给模型会报错，直接丢弃
                default -> null;
            };
        } catch (Exception e) {
            log.warn("记忆解码失败，跳过该条: {}", e.getMessage());
            return null;
        }
    }
}

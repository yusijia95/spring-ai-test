package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 业务侧使用的记忆接口：在 {@link ChatMemory} 之上多一个"取摘要"。
 * <p>
 * 摘要不通过消息通道下发，而是由 {@code AgentController} 单独拼成一段 system 内容。
 * 这样摘要生成失败时只是少一段上下文，不会破坏消息序列（例如出现孤立的 tool 消息）。
 */
public interface AgentChatMemory extends ChatMemory {

    /**
     * @return 更早对话的摘要；没有摘要时返回 null
     */
    String digest(String conversationId);
}

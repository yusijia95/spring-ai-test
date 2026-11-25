package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 业务侧使用的记忆接口：在 {@link ChatMemory} 之上多"取摘要"。
 * <p>
 * 摘要不通过消息通道下发，而是由 {@code AgentController} 单独拼成一段 system 内容。
 * 这样摘要生成失败时只是少一段上下文，不会破坏消息序列（例如出现孤立的 tool 消息）。
 * <p>
 * 用户命名空间（A2）：Redis key 带 userId 前缀（{@code chat:{userId:cid}:...}）。
 * userId 由 controller 层编码进 conversationId（复合键 {@code userId:cid}），
 * 随 {@link ChatMemory#add(String, org.springframework.ai.chat.messages.Message)} 的
 * conversationId 参数传入，不依赖任何实例状态——singleton Bean 天然并发安全。
 */
public interface AgentChatMemory extends ChatMemory {

    /**
     * @return 更早对话的摘要；没有摘要时返回 null
     */
    String digest(String conversationId);
}

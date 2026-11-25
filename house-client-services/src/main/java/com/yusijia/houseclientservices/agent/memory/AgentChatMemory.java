package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 业务侧使用的记忆接口：在 {@link ChatMemory} 之上多"取摘要"和"设置用户作用域"。
 * <p>
 * 摘要不通过消息通道下发，而是由 {@code AgentController} 单独拼成一段 system 内容。
 * 这样摘要生成失败时只是少一段上下文，不会破坏消息序列（例如出现孤立的 tool 消息）。
 * <p>
 * 用户作用域（A2）：Redis key 带 userId 前缀（{@code chat:{userId}:{cid}:...}），
 * 同一用户的 key 落在同一命名空间，便于按用户清理和审计。
 * 由 {@code AgentController} 在每轮请求开始时调用 {@link #addUserScope}，
 * 记忆顾问（advisor）在另一个线程执行时通过 volatile 字段读到正确值。
 */
public interface AgentChatMemory extends ChatMemory {

    /**
     * @return 更早对话的摘要；没有摘要时返回 null
     */
    String digest(String conversationId);

    /**
     * 设置本轮请求的 userId，用于 Redis key 命名空间。
     * 在请求开始时（controller 层）调用，advisor 线程执行时读到该值。
     *
     * @param userId 当前用户 ID
     */
    void addUserScope(String userId);
}

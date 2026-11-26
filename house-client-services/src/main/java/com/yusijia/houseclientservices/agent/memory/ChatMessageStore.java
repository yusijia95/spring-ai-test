package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 原始消息的持久化（工作记忆的权威副本）。
 * <p>
 * 为什么需要它：Redis 只做"最近窗口的读缓存"，TTL 到期或多实例切换后窗口就空了。
 * 只有把原始消息落库，用户隔天/隔周打开会话才能完整还原对话；
 * 摘要（{@link ChatDigestStore}）是压缩后的旁路上下文，不能替代原始消息。
 * <p>
 * 写入策略与 {@code agent_audit} 一致：同步写、失败只降级不抛——
 * 存储不可用时聊天照常进行，代价只是丢了这一轮的持久化。
 */
public interface ChatMessageStore {

    /**
     * 一条可展示的历史消息（带时间，给前端的会话回看接口用）。
     * 记忆层只需要 {@link Message}，不需要时间，所以两个方法分开。
     */
    record StoredMessage(String role, String content, java.time.OffsetDateTime createdAt) {
    }

    /**
     * 追加一轮消息（通常是一条 user + 一条 assistant）。
     *
     * @param conversationId 记忆作用域键（带 userId 的复合键，与 Redis key 一致）
     * @param userId         归属用户（从作用域键解析；未知时传空串）
     * @param messages       本轮消息，空文本/未知类型自动跳过
     */
    void append(String conversationId, String userId, List<Message> messages);

    /**
     * 取最近 {@code limit} 条消息，按时间正序返回（最早的在前，可直接作为窗口）。
     *
     * @return 存储不可用或无数据时返回空列表，绝不返回 null
     */
    List<Message> recent(String conversationId, int limit);

    /**
     * 取最近 {@code limit} 条消息（带创建时间），按时间正序返回。
     * 与 {@link #recent} 的区别只是多了时间字段，供前端渲染历史气泡。
     */
    List<StoredMessage> recentWithTime(String conversationId, int limit);

    /** 清空一个会话的原始消息（用户删除会话时调用） */
    void delete(String conversationId);
}

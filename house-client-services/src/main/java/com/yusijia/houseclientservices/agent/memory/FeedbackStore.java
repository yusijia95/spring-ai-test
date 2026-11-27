package com.yusijia.houseclientservices.agent.memory;

/**
 * 用户反馈存储（P1-3 反馈闭环）。
 * <p>
 * 客户对某轮回答点踩/点赞后，落一条记录。
 * 点踩记录 → golden set 候选队列（人工审核后加入评测集）。
 */
public interface FeedbackStore {

    /**
     * 保存一条反馈。
     *
     * @param conversationId 会话 ID（带 userId 前缀）
     * @param userId         用户 ID
     * @param turn           第几轮（从 1 开始）
     * @param rating         1 = 点赞，-1 = 点踩
     * @param reason         用户填写的原因（可空）
     * @param summary        该轮回答的摘要（前 200 字，用于人工审核时快速定位）
     * @return 插入的行 ID
     */
    long save(String conversationId, String userId, int turn, int rating, String reason, String summary);

    /**
     * 查询某会话的所有反馈。
     */
    java.util.List<FeedbackRecord> byConversation(String conversationId);

    /**
     * 查询所有点踩记录（golden set 候选队列），按时间倒序。
     */
    java.util.List<FeedbackRecord> badCases(int limit);

    record FeedbackRecord(long id, String conversationId, String userId, int turn,
                          int rating, String reason, String summary, java.time.Instant createdAt) {}
}

package com.yusijia.houseclientservices.agent.memory;

/**
 * 情节记忆（对话摘要）的持久化。
 * <p>
 * 只追加、只读最新一条，不做更新：摘要每次都是"上一版摘要 + 这段旧消息"合成的新文本，
 * append-only 天然避免多实例并发写互相覆盖，也留下演进过程便于排查摘要失真。
 */
public interface ChatDigestStore {

    /** @return 最新摘要；没有或存储不可用时返回 null */
    String latest(String conversationId);

    void save(String conversationId, String digest);

    void delete(String conversationId);
}

package com.yusijia.houseclientservices.agent.memory;

/**
 * 工作记忆的窗口与预算参数。
 * <p>
 * 从 {@code RedisChatMemory} 里抽出来：它描述的是"记忆策略"，不是"Redis 实现"。
 * 将来换存储（例如本地磁盘、另一个缓存）时这套参数仍然适用。
 *
 * @param maxMessages  条数硬上限，超出直接 LTRIM（一轮 ≈ 2 条，不含工具消息）
 * @param maxChars     字符预算，超出才触发压缩；<= 0 表示不做预算控制
 * @param keepRecent   压缩/截断后保留的最近条数
 * @param ttlSeconds   key 的过期时间
 * @param lockTtlMillis 生成摘要时互斥锁的 TTL
 */
public record ChatMemoryLimits(int maxMessages, int maxChars, int keepRecent,
                               long ttlSeconds, long lockTtlMillis) {
}

package com.yusijia.houseclientservices.agent.memory;

import java.util.Map;

/**
 * L3 用户画像：长期、极少变动的结构化偏好。
 * <p>
 * 与 L1（工作记忆）和 L2（情节摘要）的区别：
 * <ul>
 *   <li>按 userId 持久化，跨会话共享（不是 per-conversation）</li>
 *   <li>由模型通过 rememberUserProfile 工具写入（用户主动表达时）</li>
 *   <li>每轮注入 system prompt，让模型"记得客户是谁"</li>
 * </ul>
 * PG 不可用时所有方法静默降级（读返回空 Map，写跳过），不影响对话。
 */
public interface UserProfileStore {

    /**
     * 读取用户画像。
     *
     * @param userId 用户 ID（纯数字，不带 conversationId 前缀）
     * @return 画像 Map（key → value），无画像时返回空 Map
     */
    Map<String, String> get(String userId);

    /**
     * 写入/更新一条画像（upsert 语义）。
     * <p>
     * 写入前自动过 PiiMasker.maskForStorage 脱敏。
     *
     * @param userId  用户 ID
     * @param key     画像字段名
     * @param value   画像值
     * @param source  来源说明（如"客户主动提及"）
     */
    void upsert(String userId, String key, String value, String source);
}

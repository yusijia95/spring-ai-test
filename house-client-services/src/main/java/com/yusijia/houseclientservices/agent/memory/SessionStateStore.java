package com.yusijia.houseclientservices.agent.memory;

import java.util.Map;
import java.util.Set;

/**
 * 会话状态（session state）：当前楼盘、当前房屋、待确认认购单、预算、意向区域。
 * <p>
 * 这部分<b>不属于对话记忆</b>。放在消息历史里靠模型回想的后果是：窗口一滚动，
 * "刚才说好的那套房"就丢了，而它恰恰是办理业务的必要前提。
 * 这里改成显式的结构化事实：
 * <ul>
 *   <li>由工具在拿到真实数据时写入，不靠模型复述历史；</li>
 *   <li>每轮作为事实注入 system prompt，与消息窗口大小无关。</li>
 * </ul>
 * 实现见 {@code redis.RedisSessionStateStore}：Redis Hash 存权威值，
 * 不可用时退化为进程内 Map。
 */
public interface SessionStateStore {

    /**
     * 允许写入的字段。不在表内的 key 一律拒绝，避免模型把闲聊塞进状态。
     * 这份清单同时是 SKILL.md 里工具说明的约束源。
     */
    Set<String> ALLOWED_KEYS = Set.of(
            "currentProject", "currentHouse", "pendingSubscription", "budget", "districtPref");

    /** @return 当前会话的全部状态；没有时返回空 Map，不返回 null */
    Map<String, String> get(String conversationId);

    void put(String conversationId, String field, String value);

    void clear(String conversationId);
}

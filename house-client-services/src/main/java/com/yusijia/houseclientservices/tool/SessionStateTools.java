package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.agent.memory.SessionStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 会话状态写入工具。
 * <p>
 * 存在的理由：模型"记得刚才那套房"不该靠消息窗口，而窗口一滚动就必然忘。
 * 让模型在拿到真实数据的那一刻把它写成结构化事实，之后每轮由系统注入，
 * 业务正确性就不再依赖"模型能不能记住"。
 * <p>
 * ThreadLocal 绑定会话：工具方法签名里不能出现 conversationId（模型会瞎填），
 * 由 {@code AgentController} 在调用链路上绑定本轮会话。
 * 注意只在<b>非流式</b>（{@code .call()}）路径绑定——流式路径不挂工具。
 */
@Component
public class SessionStateTools {

    private static final Logger log = LoggerFactory.getLogger(SessionStateTools.class);

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private final SessionStateStore repository;

    public SessionStateTools(SessionStateStore repository) {
        this.repository = repository;
    }

    public static void bind(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    public static void unbind() {
        CONVERSATION_ID.remove();
    }

    @Tool(description = """
            记录本轮会话的关键业务事实，供后续轮次使用。
            当客户确认了某个楼盘、某套房屋，或认购单已生成、客户说明了预算/意向区域时调用。
            只记录工具返回结果或客户明确说过的内容，不要记录猜测和闲聊。
            字段说明：
            - currentProject：当前在谈的楼盘名
            - currentHouse：当前在谈的房屋，写成"楼盘名 房号 房屋ID"，ID 必须来自 searchHouse 结果
            - pendingSubscription：最近生成的认购单号及状态
            - budget：客户明确说出的预算
            - districtPref：客户明确说出的意向区域或户型偏好
            """)
    public String rememberSessionFact(
            @ToolParam(description = "字段名，只能是 currentProject、currentHouse、pendingSubscription、budget、districtPref 之一") String key,
            @ToolParam(description = "要记住的事实，尽量简短，不要包含客套话") String value) {
        String conversationId = CONVERSATION_ID.get();
        if (!StringUtils.hasText(conversationId)) {
            return "未记录：当前没有绑定会话。";
        }
        String field = key == null ? "" : key.trim();
        if (!SessionStateStore.ALLOWED_KEYS.contains(field)) {
            return "未记录：字段名只能是 " + String.join("、", SessionStateStore.ALLOWED_KEYS) + "。";
        }
        if (!StringUtils.hasText(value)) {
            return "未记录：内容为空。";
        }
        String fact = value.trim();
        if (fact.length() > 200) {
            fact = fact.substring(0, 200);
        }
        try {
            repository.put(conversationId, field, fact);
        } catch (RuntimeException e) {
            log.warn("写入会话状态失败: field={}, error={}", field, e.getMessage());
            return "未记录：状态存储暂不可用。";
        }
        log.info("会话状态已更新: conversationId={}, {}={}", conversationId, field, fact);
        return "已记录 " + field + "：" + fact;
    }

    /** 供 Controller 在拼接 system prompt 时读取 */
    public Map<String, String> snapshot(String conversationId) {
        return repository.get(conversationId);
    }
}

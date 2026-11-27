package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理：历史回看、删除等 REST 操作。
 * <p>
 * 从 {@link AgentController} 拆出：AgentController 只留核心对话（SSE），
 * 会话的 CRUD 归这里，职责更清晰。
 */
@RestController
@RequestMapping("/api/agent")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatMessageStore chatMessageStore;

    public ConversationController(ConversationService conversationService,
                                  ChatMessageStore chatMessageStore) {
        this.conversationService = conversationService;
        this.chatMessageStore = chatMessageStore;
    }

    /**
     * 历史会话回看：从 PG 读原始消息，不受 Redis TTL 影响。
     * <p>
     * 前端点开侧边栏的历史会话时调用，直接渲染气泡，不用"先发一句话才看到历史"。
     * 只返回最近 {@code limit} 条（默认 50，上限 200）：完整翻页交给将来的分页参数，
     * 一次拉太多既浪费带宽也会把前端渲染拖慢。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<List<ChatMessageStore.StoredMessage>> history(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest http) {
        UserContext user = Authorization.requirePermission(http, PermissionPoints.CHAT_CONVERSATION);
        conversationService.assertOwned(user, conversationId);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        String scoped = user.userId() + ":" + conversationId;
        return ResponseEntity.ok(chatMessageStore.recentWithTime(scoped, safeLimit));
    }
}

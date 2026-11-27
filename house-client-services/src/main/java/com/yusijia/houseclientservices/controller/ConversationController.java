package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import com.yusijia.houseclientservices.security.AccessDeniedException;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.PiiMasker;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理：历史回看、删除、用户画像等 REST 操作。
 * <p>
 * 从 {@link AgentController} 拆出：AgentController 只留核心对话（SSE），
 * 会话的 CRUD 归这里，职责更清晰。
 */
@RestController
@RequestMapping("/api/agent")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatMessageStore chatMessageStore;
    private final UserProfileStore userProfileStore;

    public ConversationController(ConversationService conversationService,
                                   ChatMessageStore chatMessageStore,
                                   UserProfileStore userProfileStore) {
        this.conversationService = conversationService;
        this.chatMessageStore = chatMessageStore;
        this.userProfileStore = userProfileStore;
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

    /**
     * 查询用户画像（L3 语义记忆）。
     * <p>
     * 数据范围：
     * <ul>
     *   <li>客户：只能查自己（不传 {@code userId} 参数）</li>
     *   <li>坐席：可查指定客户的画像（传 {@code userId} 参数）；
     *       骨架阶段暂不校验"分配关系"，将来接 CRM 跟进表后收紧</li>
     * </ul>
     * 返回值全部经 {@link PiiMasker#maskForStorage} 脱敏，不返回明文。
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, String>> profile(
            @RequestParam(required = false) String userId,
            HttpServletRequest http) {
        UserContext user = Authorization.requirePermission(http, PermissionPoints.DATA_PROFILE_READ);
        String targetUserId;
        if (user.isCustomer()) {
            // 客户只能查自己
            targetUserId = String.valueOf(user.userId());
        } else if (userId != null && !userId.isBlank()) {
            // 坐席查指定客户
            targetUserId = userId.trim();
        } else {
            // 坐席不传 userId：默认查自己（坐席本身也可能有画像）
            targetUserId = String.valueOf(user.userId());
        }
        Map<String, String> raw = userProfileStore.get(targetUserId);
        // 全部脱敏显示
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            masked.put(entry.getKey(), PiiMasker.maskForStorage(entry.getValue()));
        }
        return ResponseEntity.ok(masked);
    }
}

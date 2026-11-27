package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.agent.memory.FeedbackStore;
import com.yusijia.houseclientservices.exception.BizException;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 反馈闭环（P1-3）：点踩/点赞 + 点踩记录查询（golden set 候选队列）。
 * <p>
 * 客户对某轮回答不满意 → 点踩 → 落 PG → 坐席/开发者定期导出 → 人工审核 → 加入 golden set。
 */
@RestController
@RequestMapping("/api/agent")
public class FeedbackController {

    private final FeedbackStore feedbackStore;
    private final ChatMessageStore chatMessageStore;
    private final ConversationService conversationService;

    public FeedbackController(FeedbackStore feedbackStore,
                              ChatMessageStore chatMessageStore,
                              ConversationService conversationService) {
        this.feedbackStore = feedbackStore;
        this.chatMessageStore = chatMessageStore;
        this.conversationService = conversationService;
    }

    /**
     * 提交反馈（点赞/点踩）。
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Long>> submit(@RequestBody FeedbackRequest body,
                                                     HttpServletRequest http) {
        UserContext user = Authorization.requirePermission(http, PermissionPoints.FEEDBACK_SUBMIT);
        conversationService.assertOwned(user, body.conversationId());

        // 取该轮 assistant 消息的前 200 字作为摘要
        String summary = "";
        try {
            String scoped = user.userId() + ":" + body.conversationId();
            List<ChatMessageStore.StoredMessage> msgs = chatMessageStore.recentWithTime(scoped, 50);
            // 从后往前找第 turn 条 assistant 消息
            int assistantCount = 0;
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("assistant".equals(msgs.get(i).role())) {
                    assistantCount++;
                    if (assistantCount == body.turn()) {
                        String content = msgs.get(i).content();
                        summary = content != null && content.length() > 200
                                ? content.substring(0, 200) + "…"
                                : (content != null ? content : "");
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // 取不到摘要不影响反馈提交
        }

        String scoped = user.userId() + ":" + body.conversationId();
        long id = feedbackStore.save(scoped, String.valueOf(user.userId()),
                body.turn(), body.rating(), body.reason(), summary);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * 查询某会话的所有反馈。
     */
    @GetMapping("/conversations/{conversationId}/feedback")
    public ResponseEntity<List<FeedbackStore.FeedbackRecord>> byConversation(
            @PathVariable String conversationId,
            HttpServletRequest http) {
        UserContext user = Authorization.requirePermission(http, PermissionPoints.CHAT_CONVERSATION);
        conversationService.assertOwned(user, conversationId);
        String scoped = user.userId() + ":" + conversationId;
        return ResponseEntity.ok(feedbackStore.byConversation(scoped));
    }

    /**
     * 查询点踩记录（仅坐席，golden set 候选队列）。
     */
    @GetMapping("/feedback/bad-cases")
    public ResponseEntity<List<FeedbackStore.FeedbackRecord>> badCases(
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest http) {
        Authorization.requirePermission(http, PermissionPoints.FEEDBACK_BAD_READ);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(feedbackStore.badCases(safeLimit));
    }

    public record FeedbackRequest(String conversationId, int turn, int rating, String reason) {
        public FeedbackRequest {
            if (rating != 1 && rating != -1) {
                throw new BizException("rating 必须是 1（点赞）或 -1（点踩）");
            }
            if (turn < 1) {
                throw new BizException("turn 必须 >= 1");
            }
        }
    }
}

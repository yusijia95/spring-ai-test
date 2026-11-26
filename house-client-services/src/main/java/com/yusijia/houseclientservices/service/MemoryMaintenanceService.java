package com.yusijia.houseclientservices.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yusijia.houseclientservices.agent.memory.AgentChatMemory;
import com.yusijia.houseclientservices.domain.entity.Conversation;
import com.yusijia.houseclientservices.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆维护：把工作记忆的压缩从请求路径挪到后台定时任务。
 * <p>
 * 为什么不在 {@code add()} 里压：压缩要调一次外部模型（qwen-turbo），
 * 同步做会把这一轮的首字延迟拖慢几秒；而压缩本身对用户回答不是必需的，
 * 晚几分钟完成完全不影响体验。
 * <p>
 * 扫描范围只取"最近活跃"的会话（默认 2 小时内有更新）：
 * 更早的会话用户已经不再续聊，窗口超预算也不会再进 prompt，压它纯属浪费模型调用。
 * <p>
 * 多实例部署时每个实例都会跑这个任务，但 {@code RedisChatMemory} 里的
 * {@code SET NX} 锁保证同一会话只会被一个实例压缩，重复扫描最多多做几次 LRANGE。
 * <p>
 * 失败只记日志：维护任务是旁路能力，不能因为它影响正常对话。
 */
@Service
public class MemoryMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceService.class);

    private static final int STATUS_ACTIVE = 1;

    private final ConversationMapper conversationMapper;
    private final AgentChatMemory agentChatMemory;

    /** 只压最近这么久有更新的会话（分钟） */
    @Value("${agent.memory.compact.lookback-minutes:120}")
    private long lookbackMinutes;

    /** 单次最多扫描的会话数，防止会话量大时一轮任务跑太久 */
    @Value("${agent.memory.compact.max-conversations:200}")
    private int maxConversations;

    public MemoryMaintenanceService(ConversationMapper conversationMapper, AgentChatMemory agentChatMemory) {
        this.conversationMapper = conversationMapper;
        this.agentChatMemory = agentChatMemory;
    }

    /**
     * 定时压缩活跃会话。
     * <p>
     * 默认每 5 分钟一次（上一次结束后开始计时），首次延迟 1 分钟——
     * 给应用启动、连接池就绪留出余量，也避免和启动期的其他初始化抢资源。
     */
    @Scheduled(
            fixedDelayString = "${agent.memory.compact.interval-ms:300000}",
            initialDelayString = "${agent.memory.compact.initial-delay-ms:60000}")
    public void compactActiveConversations() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(lookbackMinutes);
        List<Conversation> conversations;
        try {
            conversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getStatus, STATUS_ACTIVE)
                    .ge(Conversation::getUpdatedAt, since)
                    .orderByDesc(Conversation::getUpdatedAt)
                    .last("LIMIT " + Math.max(maxConversations, 1)));
        } catch (RuntimeException e) {
            log.warn("定时压缩: 查询活跃会话失败，本轮跳过: {}", e.getMessage());
            return;
        }
        if (conversations.isEmpty()) {
            return;
        }
        int scanned = 0;
        for (Conversation conversation : conversations) {
            if (conversation.getId() == null || conversation.getOwnerUserId() == null) {
                continue;
            }
            // 记忆键是复合键 userId:cid，与 Redis key 和 PG conversation_id 完全一致
            agentChatMemory.compact(conversation.getOwnerUserId() + ":" + conversation.getId());
            scanned++;
        }
        log.info("定时压缩: 扫描 {} 个活跃会话（近 {} 分钟）", scanned, lookbackMinutes);
    }
}

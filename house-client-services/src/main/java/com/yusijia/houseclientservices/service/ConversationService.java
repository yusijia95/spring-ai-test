package com.yusijia.houseclientservices.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yusijia.houseclientservices.domain.entity.Conversation;
import com.yusijia.houseclientservices.mapper.ConversationMapper;
import com.yusijia.houseclientservices.security.AccessDeniedException;
import com.yusijia.houseclientservices.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 会话归属校验（P0-1，一人多会话）。
 * <p>
 * 记忆层（Redis/PG）以 conversationId 为键，本身不做鉴权。
 * 本服务在"用户 -> conversationId"之间加一道归属校验：
 * 用户只能读写 owner_user_id 等于自己的会话，防止跨用户串话
 * （多实例 + LB 下尤其重要，否则 A 客户能拿到 B 客户的 conversationId 就读到 B 的记忆）。
 * <p>
 * 骨架阶段：
 * <ul>
 *   <li>{@link #getOrCreate} 在 /api/agent/chat 入口调用——前端传 conversationId 时校验归属，
 *       不传则新建一条归当前用户的会话，返回权威 id；</li>
 *   <li>conversation 表只记归属，不存消息（消息仍在 Redis/PG）。</li>
 * </ul>
 */
@Service
public class ConversationService {

    private static final int STATUS_ACTIVE = 1;

    private final ConversationMapper conversationMapper;

    public ConversationService(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /**
     * 取或建当前用户的会话。
     *
     * @param user           当前登录用户
     * @param conversationId 前端传来的 id；空则新建
     * @return 权威 conversationId（雪花 Long 的字符串）
     */
    @Transactional
    public String getOrCreate(UserContext user, String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            assertOwned(user, conversationId);
            return conversationId;
        }
        Conversation created = new Conversation();
        created.setOwnerUserId(user.userId());
        created.setStatus(STATUS_ACTIVE);
        conversationMapper.insert(created);
        return String.valueOf(created.getId());
    }

    /** 校验 conversationId 归当前用户所有，否则 403。 */
    public void assertOwned(UserContext user, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        long id;
        try {
            id = Long.parseLong(conversationId.trim());
        } catch (NumberFormatException e) {
            // 非雪花 id 的历史会话（如早期无鉴权期生成的 UUID）：骨架阶段放行并记日志，
            // 不做归属断言。将来接真身份后应收紧为拒绝。
            return;
        }
        Conversation conv = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, id)
                .last("LIMIT 1"));
        if (conv == null) {
            // 表里没有该会话（可能是老会话或别处生成的）：骨架阶段放行，记日志。
            // 收紧路径：接真身份后改为抛 AccessDeniedException。
            return;
        }
        if (!conv.getOwnerUserId().equals(user.userId())) {
            throw new AccessDeniedException("无权访问该会话");
        }
    }

    /** 当前用户的进行中会话列表（按更新时间倒序）。 */
    public List<Conversation> listMine(UserContext user) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getOwnerUserId, user.userId())
                .eq(Conversation::getStatus, STATUS_ACTIVE)
                .orderByDesc(Conversation::getUpdatedAt));
    }
}

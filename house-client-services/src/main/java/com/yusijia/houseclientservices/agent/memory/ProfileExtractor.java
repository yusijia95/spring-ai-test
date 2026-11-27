package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * 从对话消息中提取长期偏好（L3 用户画像）。
 * <p>
 * 在压缩流程里顺带调用：压缩本来就要把旧消息喂给模型，
 * 多要一次"顺便提取偏好"的开销远小于单独再调一次模型。
 * <p>
 * 实现必须满足：
 * <ul>
 *   <li>失败（模型超时、返回格式错误等）只记 warn，不抛异常——画像是旁路能力</li>
 *   <li>只提取客户<b>明确说过</b>的偏好，不推测</li>
 *   <li>返回空 Map 表示"这段对话里没有可提取的画像"</li>
 * </ul>
 */
public interface ProfileExtractor {

    /**
     * 从消息列表中提取用户画像字段。
     *
     * @param userId   用户 ID（纯数字，不带 conversationId 前缀）
     * @param messages 被压缩的旧消息（按时间正序）
     * @return 提取到的画像字段（key → value），无提取结果时返回空 Map
     */
    Map<String, String> extract(String userId, List<Message> messages);
}

package com.yusijia.houseclientservices.agent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 把"窗口头部即将被丢弃的那段对话"压成摘要。
 * <p>
 * 抽成接口是为了两件事：
 * 1. 单测里可以塞假实现，验证"超预算 → 压缩 → 窗口截短"的决策，不用真调模型；
 * 2. 以后换更便宜/更快的摘要模型不影响记忆实现。
 */
public interface ChatMemoryCompressor {

    /**
     * @param previousDigest 上一次的摘要，可能为 null
     * @param messages       本次要合并进去的旧消息（按时间正序）
     * @return 新摘要；压缩失败返回 null（调用方退回截断）
     */
    String compress(String previousDigest, List<Message> messages);
}

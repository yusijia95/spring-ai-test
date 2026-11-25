package com.yusijia.houseclientservices.agent.memory.llm;

import com.yusijia.houseclientservices.agent.memory.ChatMemoryCompressor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用便宜模型把旧对话压成事实摘要。
 * <p>
 * 摘要只保留"之后还可能用到的事实"，明确禁止推测：摘要里编出来的数字会被后续每一轮当成事实，
 * 比不压缩更危险。所以宁可输出偏短、缺失，也不能补全。
 */
public class LlmChatMemoryCompressor implements ChatMemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(LlmChatMemoryCompressor.class);

    /** 摘要长度上限：超预算时，摘要本身也必须可控 */
    private static final int MAX_DIGEST_CHARS = 600;

    private static final String SYSTEM = """
            你是客服对话的摘要器。把给定的对话压缩成一段事实清单，供后续轮次当作背景。

            只保留：
            - 客户身份线索（姓名、证件后四位、企业名称）
            - 已在谈的楼盘、房屋坐落/房号、房屋ID
            - 认购单号及其状态（已生成/已撤销）
            - 预算、意向区域、户型等偏好
            - 已确认的结论，以及明确还没办完的事项

            严格要求：
            - 只写对话里出现过的事实，禁止推测、补全、编造任何数字或编号。
            - 证件号、手机号只能保留最后 4 位（如 1234），禁止复述完整号码。
            - 不要写寒暄、语气词、客服的客套话。
            - 输出纯文本，不超过 300 字，不要 Markdown 标题，不要分点符号可用"；"分隔。
            """;

    private final ChatClient chatClient;
    private final String model;

    public LlmChatMemoryCompressor(ChatClient chatClient, String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    @Override
    public String compress(String previousDigest, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            log.warn("摘要压缩收到空消息列表，跳过");
            return null;
        }
        try {
            log.debug("开始压缩对话摘要: 旧消息 {} 条，已有摘要 {} 字",
                    messages.size(), previousDigest == null ? 0 : previousDigest.length());
            String answer = chatClient.prompt()
                    .system(SYSTEM)
                    .user(buildInput(previousDigest, messages))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                log.warn("摘要模型返回空内容（model={}），本轮退回截断", model);
                return null;
            }
            String digest = answer.trim();
            log.debug("摘要生成完成: model={}, {} 字", model, digest.length());
            if (digest.length() > MAX_DIGEST_CHARS) {
                digest = digest.substring(0, MAX_DIGEST_CHARS);
            }
            return digest;
        } catch (RuntimeException e) {
            log.warn("摘要模型调用失败（model={}），本轮退回截断: {}", model, e.getMessage());
            return null;
        }
    }

    private String buildInput(String previousDigest, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(previousDigest)) {
            sb.append("【已有摘要】\n").append(previousDigest).append("\n\n");
        }
        sb.append("【新增对话】\n");
        for (Message message : messages) {
            String text = message.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            sb.append(message.getMessageType() == MessageType.USER ? "客户：" : "客服：")
                    .append(text.trim())
                    .append('\n');
        }
        return sb.toString();
    }
}

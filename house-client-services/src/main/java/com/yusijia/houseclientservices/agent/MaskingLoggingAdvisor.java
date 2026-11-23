package com.yusijia.houseclientservices.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 替代 {@code SimpleLoggerAdvisor} 的 AI 调用日志。
 * <p>
 * 为什么换掉它：{@code SimpleLoggerAdvisor} 会在 DEBUG 级别把整轮 prompt 原样打出来
 * （system 全文 + 历史消息 + 用户输入），客户办理认购时说的姓名、证件号、手机号就明文落盘，
 * 还会进日志平台。这里改成：
 * <ul>
 *   <li>INFO（默认）：每次只打一行摘要，<b>不含任何消息正文</b>；</li>
 *   <li>DEBUG 且 {@code agent.log.prompt.enabled=true}：额外打脱敏 + 截断后的正文，
 *       仅用于临时排查。</li>
 * </ul>
 * 接入方式：实现 {@link BaseAdvisor}，只写 {@link #before}/{@link #after}，call 路径由
 * 接口默认实现串起来；流式路径覆写 {@link #adviseStream} 以便用聚合器拿到完整回复，
 * 也能保住 {@code after} 里的耗时统计（否则最后一帧只有增量内容）。
 */
public class MaskingLoggingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MaskingLoggingAdvisor.class);

    /** 单次正文日志上限，避免一轮几千字的 prompt 把日志撑爆 */
    private static final int BODY_MAX_CHARS = 2000;

    private final boolean promptLogEnabled;

    private final int order;

    /**
     * 仅用于 call 路径的耗时统计：{@code before/after} 在同一个线程上成对执行。
     * <p>
     * <b>流式路径不能用它</b>——{@code after} 不在调用线程上执行，写进去就没人 remove，
     * 线程池里的线程会一直留一份。所以 {@link #adviseStream} 用局部变量算耗时，
     * 见那里的注释。
     */
    private final ThreadLocal<Long> requestStartNanos = new ThreadLocal<>();

    public MaskingLoggingAdvisor(@Value("${agent.log.prompt.enabled:false}") boolean promptLogEnabled) {
        this(promptLogEnabled, 0);
    }

    public MaskingLoggingAdvisor(boolean promptLogEnabled, int order) {
        this.promptLogEnabled = promptLogEnabled;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        requestStartNanos.set(System.nanoTime());
        safeLogRequest(chatClientRequest);
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        Long startNanos = requestStartNanos.get();
        requestStartNanos.remove();
        try {
            logResponse(chatClientResponse, startNanos);
        } catch (RuntimeException e) {
            log.debug("AI 响应日志打印失败: {}", e.getMessage());
        }
        return chatClientResponse;
    }

    /** 日志失败绝不能影响对话 */
    private void safeLogRequest(ChatClientRequest request) {
        try {
            logRequest(request);
        } catch (RuntimeException e) {
            log.debug("AI 请求日志打印失败: {}", e.getMessage());
        }
    }

    /**
     * 流式路径：必须先聚合，最后一帧的 finishReason 才带得到完整回复，
     * 否则 after 里统计到的只是增量片段。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        long startNanos = System.nanoTime();
        // 不调 before()：那里会写 ThreadLocal，而流式路径的 after() 不会在调用线程上执行，
        // 写进去就没人 remove，线程池线程会一直留一份。耗时用局部变量，日志直接打。
        safeLogRequest(chatClientRequest);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                streamAdvisorChain.nextStream(chatClientRequest),
                aggregated -> {
                    try {
                        logResponse(aggregated, startNanos);
                    } catch (RuntimeException e) {
                        log.debug("AI 响应日志打印失败: {}", e.getMessage());
                    }
                });
    }

    private void logRequest(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        ChatOptions options = request.prompt().getOptions();
        log.info("AI 请求: conversationId={}, 消息{}条, system {}字, 工具{}个, 模型={}",
                conversationId(request),
                instructions.size(),
                systemChars(instructions),
                toolCount(options),
                options == null || options.getModel() == null ? "-" : options.getModel());

        if (!bodyLogEnabled()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (Message message : instructions) {
            body.append('[').append(message.getMessageType().getValue()).append("] ")
                    .append(emptyIfNull(message.getText()))
                    .append('\n');
        }
        log.debug("AI 请求正文（已脱敏）:\n{}", PiiMasker.truncate(PiiMasker.mask(body.toString()), BODY_MAX_CHARS));
    }

    private void logResponse(ChatClientResponse chatClientResponse, @Nullable Long startNanos) {
        ChatResponse chatResponse = chatClientResponse == null ? null : chatClientResponse.chatResponse();
        String text = responseText(chatResponse);
        long costMs = startNanos == null ? -1L : (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("AI 响应: 耗时{}ms, 工具调用={}, 回复{}字",
                costMs,
                chatResponse != null && chatResponse.hasToolCalls() ? "是" : "否",
                text == null ? 0 : text.length());

        if (!bodyLogEnabled() || text == null || text.isEmpty()) {
            return;
        }
        log.debug("AI 回复正文（已脱敏）: {}", PiiMasker.truncate(PiiMasker.mask(text), BODY_MAX_CHARS));
    }

    private boolean bodyLogEnabled() {
        // 两道闸：开关默认关；日志级别也要真的是 DEBUG，避免生产随手把开关打开就打全量
        return promptLogEnabled && log.isDebugEnabled();
    }

    private String conversationId(ChatClientRequest request) {
        Object value = request.context() == null ? null : request.context().get(ChatMemory.CONVERSATION_ID);
        return value == null ? "-" : value.toString();
    }

    private int systemChars(List<Message> instructions) {
        int chars = 0;
        for (Message message : instructions) {
            if (message instanceof SystemMessage) {
                chars += message.getText() == null ? 0 : message.getText().length();
            }
        }
        return chars;
    }

    private int toolCount(@Nullable ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolingOptions)) {
            return 0;
        }
        int callbacks = toolingOptions.getToolCallbacks() == null ? 0 : toolingOptions.getToolCallbacks().size();
        int names = toolingOptions.getToolNames() == null ? 0 : toolingOptions.getToolNames().size();
        return callbacks + names;
    }

    private String responseText(@Nullable ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private String emptyIfNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }
}

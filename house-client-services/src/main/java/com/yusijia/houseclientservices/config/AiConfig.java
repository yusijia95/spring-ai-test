package com.yusijia.houseclientservices.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体装配。
 * <p>
 * 提示词与工具<b>不再</b>在这里写死：
 * - 铁律、回复格式等硬约束见 {@code agent/AgentPrompts#COMMON}，永远下发；
 * - 流程提示词来自 {@code resources/skills/<name>/SKILL.md}，由 {@code agent/SkillSelector} 选择；
 * - 工具按选中的技能裁剪，见 {@code agent/ToolRegistry}。
 * <p>
 * 新增能力只需加一个 SKILL.md，不用改 Java 代码，也不需要在一条巨型 system prompt 里找位置，
 * 更不会让没被问到的流程占用上下文和工具槽位。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient houseServiceChatClient(DashScopeChatModel dashScopeChatModel,
                                              ChatMemory chatMemory) {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model("qwen-max")
                .temperature(0.0d)
                // 注意：不要设置 toolChoice("required")。本流程要求"信息不全时只追问不调工具"，
                // 全局强制调用工具会破坏该行为。防幻觉由下游 AgentController 的责任链兜底。
                .build();
        return ChatClient.builder(dashScopeChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                .defaultOptions(options)
                .build();
    }

    /**
     * 意图选择器专用的 ChatClient。
     * <p>
     * 这是本方案唯一多出来的一次 LLM 往返（输出只有十几个 token），
     * 模型可用 {@code agent.skill-selector.model} 换成更便宜的 qwen-turbo。
     * 不挂记忆 advisor 是为了让分类不受历史对话干扰。
     */
    @Bean
    public ChatClient skillSelectorChatClient(DashScopeChatModel dashScopeChatModel,
            @Value("${agent.skill-selector.model:qwen-max}") String model) {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(model)
                .temperature(0.0d)
                .build();
        return ChatClient.builder(dashScopeChatModel).defaultOptions(options).build();
    }
}

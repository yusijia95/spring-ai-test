package com.yusijia.houseclientservices.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

@Bean
    public ChatClient houseServiceChatClient(DashScopeChatModel dashScopeChatModel,
                                             ChatMemory chatMemory,
                                             SubscriptionTools subscriptionTools) {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .temperature(0.2d)
//                .toolChoice("required")  // 强制模型必须调用工具，禁止直接生成文本
                .build();
        return ChatClient.builder(dashScopeChatModel)
                .defaultSystem("""
                        你是房产客服智能体，只办理认购生成与认购撤销，语气简洁、礼貌。
                        
                        ## 生成认购
                        - 必须同时具备：房屋坐落、购买方姓名、购买方证件号码。
                        - 缺任何一项时，只向客户追问缺失项，禁止猜测或编造，禁止调用 createSubscription。
                        - 三项齐全后，**必须立即调用 createSubscription 工具**，严禁直接生成回复文本。
                        - 工具返回结果后，原样转述给客户，不得修改编号或内容。
                        
                        ## 撤销认购
                        - 需要房屋坐落或购买方姓名至少一项。信息不足时先追问。
                        - 必须先调用 findSubscription，不得直接撤销。
                        - 查到结果后，必须向客户展示房屋坐落和购房人姓名，明确请客户确认是否撤销。
                        - 多条匹配时，请客户指定更精确的坐落或姓名，再次查询后再确认。
                        - 只有客户明确表示确认撤销后，才能调用 cancelSubscription，传入查询得到的编号，且 confirmed=true。
                        - 客户未确认时，禁止调用 cancelSubscription，或不得将 confirmed 设为 true。
                        
                        ## 严格禁止
                        - 绝对禁止编造认购编号。编号只能来自 createSubscription 或 findSubscription 的工具返回结果。
                        - 绝对禁止在未调用 createSubscription 的情况下回复"认购已生成"或"认购业务已生成"。
                        - 每次回复认购结果时，必须是工具返回的原始内容，不得添加、修改或编造任何信息。
                        - 工具返回什么就如实转述，不得添加、修改或编造任何信息。
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                .defaultTools(subscriptionTools)
                .defaultOptions(options)
                .build();
    }
}

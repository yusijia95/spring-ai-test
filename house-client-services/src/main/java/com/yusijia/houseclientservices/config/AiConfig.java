package com.yusijia.houseclientservices.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient houseServiceChatClient(DashScopeChatModel dashScopeChatModel,
                                              ChatMemory chatMemory,
                                              SubscriptionTools subscriptionTools,
                                              ToolCallbackProvider[] mcpToolProviders) {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model("qwen-max")
                .temperature(0.0d)
                // 注意：不要设置 toolChoice("required")。本流程要求"信息不全时只追问不调工具"，
                // 全局强制调用工具会破坏该行为。防幻觉由下游 AgentController 的责任链兜底。
                .build();
        // #region agent log
        try {
            List<String> names = new ArrayList<>();
            int providerCount = mcpToolProviders == null ? -1 : mcpToolProviders.length;
            if (mcpToolProviders != null) {
                for (ToolCallbackProvider provider : mcpToolProviders) {
                    if (provider == null) {
                        continue;
                    }
                    ToolCallback[] callbacks = provider.getToolCallbacks();
                    if (callbacks == null) {
                        continue;
                    }
                    for (ToolCallback callback : callbacks) {
                        names.add(callback.getToolDefinition().name());
                    }
                }
            }
            try (FileWriter fw = new FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
                fw.write("{\"sessionId\":\"be087e\",\"runId\":\"pre-fix\",\"hypothesisId\":\"H1\",\"location\":\"AiConfig.java:houseServiceChatClient\",\"message\":\"mcp tools registered at ChatClient build\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"providerCount\":" + providerCount + ",\"mcpToolCount\":" + names.size() + ",\"mcpToolNames\":\"" + String.join(",", names).replace("\"", "'") + "\"}}\n");
            }
        } catch (Exception ignored) {
        }
        // #endregion
        return ChatClient.builder(dashScopeChatModel)
                .defaultSystem("""
                        你是房产客服智能体，办理认购生成、认购撤销，以及房屋周边环境查询。语气简洁、礼貌。

                        ## 铁律
                        - 绝对禁止编造房屋、客户、单位、业务编号或周边场所。
                        - 所有 ID 必须来自工具返回结果，禁止自行推测或拼接。
                        - 绝对禁止向客户暴露内部动作：回复中不得出现"调用 searchHouse""参数""工具"等字样，
                          也不得复述工具名、参数或查询语句。需要查询时直接调用工具，不要描述调用过程。
                        - 绝对禁止在未调用 createSubscription 的情况下回复"认购已生成"或"认购业务已生成"。
                        - ID 只能原样复制工具返回结果中的值（例如返回 "ID=5001" 就只能是 5001）。
                          严禁自行编造、改写 ID，严禁使用 "P"、"H" 等字母开头的自造编号。
                          未拿到工具返回的 ID 就说明还没有查询，必须先去查询，不能凭空填写。

                        ## 周边环境查询
                        - 客户询问某地址周边环境、配套、设施、交通、学校、医院、商场时，必须查询地图，禁止凭记忆列举场所。
                        - 必须先调用 map_geocode 定位该地址。location 必须原样复制地理编码结果中的坐标，禁止自行填写或改写经纬度。
                        - 若编码结果的区县与客户给出的区不一致，向客户说明定位失败，禁止用错误坐标继续搜周边。
                        - 再调用 map_search_places。query 每次只传一个词，禁止使用“设施”；应分别检索：地铁站、公交站、学校、医院、商场、超市、公园、银行。
                        - location 格式为“纬度,经度”，必须与 map_geocode 返回的 lat、lng 完全一致。
                        - 只转述地图查询结果中的名称、地址、距离等信息，不得补充结果里没有的地点。
                        - 禁止让客户“自行用地图/联系社区”；禁止因为本职是认购就拒绝周边查询。

                        ## 生成认购
                        1. 从客户描述中提取：房屋坐落、购买方姓名、购买方证件号码。
                           企业购房时，购买方为单位全称加单位证件号码。
                        2. 只要客户提供了房屋坐落，立即调用 searchHouse 查询房屋，不要先追问姓名或证件号码。
                           - 返回 0 条：告知客户未找到，请其提供更精确的坐落，然后结束本轮，禁止继续。
                           - 返回多条：列出候选房屋，请客户指定具体房屋。
                           - 返回唯一一条：向客户确认已定位该房屋，进入下一步。
                        3. 房屋确认后，若购买方姓名或证件号码缺失，只追问缺失项。
                           信息不全时禁止调用 createSubscription。
                        4. 个人购房调用 searchPerson（传姓名或证件号码）；企业购房调用 searchOrg（传单位全称）。
                           - 返回 0 条：告知客户未建档，请其核对姓名与证件号码。
                           - 返回多条：请客户确认是哪一位/哪一家。
                        5. 确认唯一房屋、唯一购买主体后，**必须立即调用 createSubscription**，
                           houseIds 与 personIds（或 orgIds）传工具返回的真实 ID，多个 ID 用英文逗号分隔。
                           生成认购不需要客户二次确认，信息齐全就直接生成。
                        6. 工具返回后，原样转述给客户，不得修改编号、金额或任何内容。

                        ## 撤销认购
                        - 需要房屋坐落或购买方姓名至少一项，不足时先追问。
                        - 必须先调用 findSubscription，不得直接撤销。
                        - 查到结果后，必须向客户展示房屋坐落和购房人姓名，明确请客户确认是否撤销。
                        - 多条匹配时，请客户提供更精确的坐落或姓名，再次查询后再确认。
                        - 只有客户明确表示确认撤销后，才能调用 cancelSubscription，传入查询得到的编号，且 confirmed=true。
                        - 客户未确认时，禁止调用 cancelSubscription。

                        ## 转述要求
                        - 工具返回什么就如实转述，不得添加、修改或编造任何信息。
                        - 工具返回"生成失败"或"撤销失败"时，把失败原因转述给客户，不要假装成功。
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                .defaultTools(subscriptionTools)
                .defaultToolCallbacks((ToolCallbackProvider) () -> {
                    List<ToolCallback> wrapped = new ArrayList<>();
                    if (mcpToolProviders == null) {
                        return new ToolCallback[0];
                    }
                    for (ToolCallbackProvider provider : mcpToolProviders) {
                        if (provider == null || provider.getToolCallbacks() == null) {
                            continue;
                        }
                        for (ToolCallback callback : provider.getToolCallbacks()) {
                            wrapped.add(new ToolCallback() {
                                @Override
                                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                                    return callback.getToolDefinition();
                                }

                                @Override
                                public String call(String toolInput) {
                                    // #region agent log
//                                    try (FileWriter fw = new FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
//                                        String name = callback.getToolDefinition().name();
//                                        String inputPreview = toolInput == null ? "" : toolInput.substring(0, Math.min(200, toolInput.length())).replace("\"", "'").replace("\n", " ");
//                                        fw.write("{\"sessionId\":\"be087e\",\"runId\":\"pre-fix\",\"hypothesisId\":\"H2\",\"location\":\"AiConfig.java:mcpTool.call\",\"message\":\"mcp tool invoked\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"toolName\":\"" + name.replace("\"", "'") + "\",\"inputPreview\":\"" + inputPreview + "\"}}\n");
//                                    } catch (Exception ignored) {
//
//                                    }
                                    // #endregion
                                     String name = callback.getToolDefinition().name();
                                     String actualInput = toolInput;
                                     if ("map_search_places".equals(name)) {
                                         actualInput = BaiduMapToolGuard.rewriteSearchPlacesInput(toolInput);
                                         // #region agent log
//                                         try (FileWriter fw = new FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
//                                             fw.write("{\"sessionId\":\"be087e\",\"runId\":\"post-fix\",\"hypothesisId\":\"H4\",\"location\":\"AiConfig.java:mcpTool.rewrite\",\"message\":\"forced geocode location onto map_search_places\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"originalInput\":\"" + (toolInput == null ? "" : toolInput.substring(0, Math.min(200, toolInput.length())).replace("\"", "'").replace("\n", " ")) + "\",\"rewrittenInput\":\"" + actualInput.replace("\"", "'") + "\",\"forcedLocation\":\"" + String.valueOf(BaiduMapToolGuard.lastLocation()).replace("\"", "'") + "\"}}\n");
//                                         } catch (Exception ignored) {
//                                         }
                                         // #endregion
                                     }
                                      String toolOutput;
                                      if ("map_search_places".equals(name)) {
                                          List<String> queries = BaiduMapToolGuard.splitQueries(actualInput);
                                          if (queries.size() > 1) {
                                              StringBuilder merged = new StringBuilder();
                                              for (String query : queries) {
                                                  String one = BaiduMapToolGuard.withQuery(actualInput, query);
                                                  String oneOut = BaiduMapToolGuard.unwrapMcpContent(callback.call(one));
                                                  if (!merged.isEmpty()) {
                                                      merged.append("\n");
                                                  }
                                                  merged.append(query).append("：").append(BaiduMapToolGuard.formatSearchPlacesOutput(oneOut));
                                              }
                                              toolOutput = merged.toString();
                                          } else {
                                              toolOutput = BaiduMapToolGuard.formatSearchPlacesOutput(
                                                      BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput)));
                                          }
                                      } else if ("map_geocode".equals(name)) {
                                          String rawOutput = BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput));
                                          BaiduMapToolGuard.rememberGeocode(rawOutput);
                                          toolOutput = BaiduMapToolGuard.formatGeocodeOutput(rawOutput);
                                      } else {
                                          toolOutput = BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput));
                                      }
                                    // #region agent log
//                                    try (FileWriter fw = new FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
//                                        String outputPreview = toolOutput == null ? "" : toolOutput.substring(0, Math.min(800, toolOutput.length())).replace("\"", "'").replace("\n", " ").replace("\r", " ");
//                                        fw.write("{\"sessionId\":\"be087e\",\"runId\":\"post-fix\",\"hypothesisId\":\"H3\",\"location\":\"AiConfig.java:mcpTool.result\",\"message\":\"mcp tool result\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"toolName\":\"" + name.replace("\"", "'") + "\",\"outputPreview\":\"" + outputPreview + "\"}}\n");
//                                    } catch (Exception ignored) {
//                                    }
                                    // #endregion
                                    return toolOutput;
                                }
                            });
                        }
                    }
                    return wrapped.toArray(ToolCallback[]::new);
                })
                .defaultOptions(options)
                .build();
    }
}

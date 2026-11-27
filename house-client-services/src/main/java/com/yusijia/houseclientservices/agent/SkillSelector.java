package com.yusijia.houseclientservices.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 意图选择器：用一次短 LLM 调用决定本轮需要哪些技能。
 * <p>
 * 为什么用大模型而不是关键词：一句话可能同时命中多个能力（例如"联发云启周边还有哪些住宅，价格怎么样，以及周边环境"），
 * 关键词难以准确拆分语义边界。LLM 虽然多一次往返，但分类准确且输出只有十几个 token，成本可忽略。
 * <p>
 * 未来升级 Spring AI 2.0 后可直接换成 {@code org.springaicommunity.agent.tools.SkillsTool}，
 * 本方案的 SKILL.md 格式刻意保持与 SkillsTool 兼容。
 */
@Component
public class SkillSelector {

    private static final Logger log = LoggerFactory.getLogger(SkillSelector.class);

    private static final String SYSTEM_PROMPT = """
            你是意图分类器。根据用户最新一句话，从技能清单中选出本轮需要执行的技能。

            规则：
            - 可以多选，宁多勿漏：漏选会导致答非所问，多选只是多给一点上下文。
            - 用户一句话里问了多个方面（例如既问住宅又问周边），必须把相关技能全部选出。
            - 只要句子里出现业务对象或业务动作（房屋、楼盘、房价、认购、撤销、路线、周边、
              地铁、预算、面积、姓名、证件号等），就必须选出对应技能，**禁止输出 none**。
              只给出姓名、证件号、房号这类"业务要素"也算在办认购，选 subscribe。
            - 只有纯寒暄、道谢、告别、确认收到（例如"你好""谢谢""好的""收到""嗯"）才输出 none。
            - 问当前日期、星期几、几点这类时间问题，输出 none：事实已在系统提示里给到，
              不需要任何技能，也不需要检索。
            - 只输出技能名，用英文逗号分隔；不需要任何技能时输出 none；
              不要解释、不要编号、不要代码块、不要输出其它文字。
            """;

    private final ChatClient skillSelectorChatClient;
    private final SkillCatalog catalog;

    /** 会话粘性：key 为 conversationId，超过上限按最久未用淘汰 */
    private final Map<String, List<String>> lastSelection = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 500;
                }
            });

    public SkillSelector(@Qualifier("skillSelectorChatClient") ChatClient skillSelectorChatClient,
                         SkillCatalog catalog) {
        this.skillSelectorChatClient = skillSelectorChatClient;
        this.catalog = catalog;
    }

    /**
     * 根据消息选择本轮技能集合。
     * <p>
     * 三种结果要分清：
     * <ul>
     *   <li><b>非空列表</b>：正常命中，下发对应技能正文；</li>
     *   <li><b>空列表</b>：模型明确判定"本轮无技能"（纯寒暄/道谢/确认）。
     *       调用方只发 {@code AgentPrompts.COMMON}，工具列表为空；</li>
     *   <li><b>全部技能</b>：选择器本身出错（超时/解析失败）。这是安全网，
     *       不能因为分类器挂了就丢掉业务能力。</li>
     * </ul>
     *
     * @param conversationId 会话 ID（用于粘性选择）
     * @param message        用户最新一句话
     * @return 选中的技能列表，可能为空（表示只发 COMMON），不会为 null
     */
    public List<Skill> select(String conversationId, String message) {
        return select(conversationId, message, null);
    }

    /**
     * 根据消息选择本轮技能集合（灰度感知）。
     *
     * @param userId 当前用户 ID（null 时走基础版本）
     */
    public List<Skill> select(String conversationId, String message, String userId) {
        // message 为空 → 返回全部
        if (message == null || message.isBlank()) {
            return catalog.all(userId);
        }

        try {
            String rawOutput = skillSelectorChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(catalog.index(userId) + "\n\n用户最新一句话：" + message)
                    .call()
                    .content();

            log.debug("意图选择器原始输出: [{}]", rawOutput);

            List<String> hitNames = parseNames(rawOutput, userId);
            if (!hitNames.isEmpty()) {
                if (conversationId != null && !conversationId.isBlank()) {
                    lastSelection.put(conversationId, hitNames);
                }
                List<Skill> skills = catalog.byNames(hitNames, userId);
                log.debug("选中的技能: {}", skills.stream().map(Skill::name).toList());
                return skills;
            }
        } catch (Exception e) {
            log.warn("意图选择器调用失败，降级返回全部技能: {}", e.getMessage());
            return catalog.all(userId);
        }

        // 未命中但粘性里有记录（"好的""确认"这类短句靠这个）
        if (conversationId != null && !conversationId.isBlank()) {
            List<String> previous = lastSelection.get(conversationId);
            if (previous != null && !previous.isEmpty()) {
                List<Skill> skills = catalog.byNames(previous, userId);
                log.debug("使用粘性选择: {}", skills.stream().map(Skill::name).toList());
                return skills;
            }
        }

        log.debug("意图选择器判定本轮无技能，只下发 COMMON");
        return List.of();
    }

    /**
     * 从 LLM 输出中解析出有效的技能名。
     * 使用词边界匹配，避免部分匹配（如 "cancel" 不应匹配 "surroundings" 中不存在的子串）。
     */
    private List<String> parseNames(String output) {
        return parseNames(output, null);
    }

    private List<String> parseNames(String output, String userId) {
        List<String> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        for (Skill skill : catalog.all(userId)) {
            String name = skill.name();
            Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(name) + "(?![A-Za-z0-9_-])");
            if (pattern.matcher(output).find()) {
                result.add(name);
            }
        }
        return result;
    }
}

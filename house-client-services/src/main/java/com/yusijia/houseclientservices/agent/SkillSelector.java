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
            - 用户只是在寒暄、道谢、确认、回答你的追问、补充姓名证件号时，输出 none。
            - 只输出技能名，用英文逗号分隔；不要解释、不要编号、不要代码块、不要输出其它文字。
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
     *
     * @param conversationId 会话 ID（用于粘性选择）
     * @param message        用户最新一句话
     * @return 选中的技能列表（不会为 null）
     */
    public List<Skill> select(String conversationId, String message) {
        // message 为空 → 返回全部
        if (message == null || message.isBlank()) {
            return catalog.all();
        }

        try {
            String rawOutput = skillSelectorChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(catalog.index() + "\n\n用户最新一句话：" + message)
                    .call()
                    .content();

            log.debug("意图选择器原始输出: [{}]", rawOutput);

            List<String> hitNames = parseNames(rawOutput);
            if (!hitNames.isEmpty()) {
                if (conversationId != null && !conversationId.isBlank()) {
                    lastSelection.put(conversationId, hitNames);
                }
                List<Skill> skills = catalog.byNames(hitNames);
                log.debug("选中的技能: {}", skills.stream().map(Skill::name).toList());
                return skills;
            }
        } catch (Exception e) {
            log.warn("意图选择器调用失败，降级返回全部技能: {}", e.getMessage());
            return catalog.all();
        }

        // 未命中但粘性里有记录（"好的""确认"这类短句靠这个）
        if (conversationId != null && !conversationId.isBlank()) {
            List<String> previous = lastSelection.get(conversationId);
            if (previous != null && !previous.isEmpty()) {
                List<Skill> skills = catalog.byNames(previous);
                log.debug("使用粘性选择: {}", skills.stream().map(Skill::name).toList());
                return skills;
            }
        }

        // 完全未命中（开场寒暄）：放开全部
        log.debug("意图选择器未命中，返回全部技能");
        return catalog.all();
    }

    /**
     * 从 LLM 输出中解析出有效的技能名。
     * 使用词边界匹配，避免部分匹配（如 "cancel" 不应匹配 "surroundings" 中不存在的子串）。
     */
    private List<String> parseNames(String output) {
        List<String> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        for (Skill skill : catalog.all()) {
            String name = skill.name();
            Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(name) + "(?![A-Za-z0-9_-])");
            if (pattern.matcher(output).find()) {
                result.add(name);
            }
        }
        return result;
    }
}

package com.yusijia.houseclientservices.agent;

import java.util.List;

/**
 * 一个技能 = 一段流程提示词 + 一组工具，来自 classpath 下的 SKILL.md。
 *
 * @param name        技能唯一标识（与目录名一致）
 * @param description 给选择器看的简短描述，说明何时使用
 * @param tools       该技能需要的工具名列表
 * @param body        流程提示词正文，拼接到 system prompt 中
 */
public record Skill(String name, String description, List<String> tools, String body) {

    public Skill {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}

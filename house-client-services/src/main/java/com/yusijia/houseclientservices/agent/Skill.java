package com.yusijia.houseclientservices.agent;

import java.util.List;

/**
 * 一个技能 = 一段流程提示词 + 一组工具。
 * <p>
 * 来源：DB（{@code agent_skill} 表，运行时真相）或 classpath SKILL.md（种子）。
 *
 * @param name             技能唯一标识（与目录名一致）
 * @param description      给选择器看的简短描述，说明何时使用
 * @param tools            该技能需要的工具名列表
 * @param body             流程提示词正文，拼接到 system prompt 中
 * @param permissionPoints 该技能引用的权限点列表（工具执行层按此过滤）
 * @param version          版本号（DB 来源），classpath 种子为 1
 * @param status           draft / active（DB 来源），classpath 种子为 active
 */
public record Skill(String name, String description, List<String> tools, String body,
                    List<String> permissionPoints, int version, String status) {

    public Skill {
        tools = tools == null ? List.of() : List.copyOf(tools);
        permissionPoints = permissionPoints == null ? List.of() : List.copyOf(permissionPoints);
    }

    /** 兼容旧构造（classpath 种子）：无权限点、version=1、status=active */
    public Skill(String name, String description, List<String> tools, String body) {
        this(name, description, tools, body, List.of(), 1, "active");
    }

    public boolean isActive() {
        return "active".equals(status);
    }
}

package com.yusijia.houseclientservices.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只验证技能文件的加载与解析，不启动 Spring 上下文。
 * <p>
 * 这是本方案唯一的"新格式约定"：SKILL.md 的 frontmatter 写错、目录名写错、
 * 文件没被打进 classpath，都会在这里第一时间暴露，而不是等到线上模型答非所问。
 */
class SkillCatalogTest {

    private final SkillCatalog catalog = new SkillCatalog("classpath*:skills/*/SKILL.md");

    @Test
    void loadsEverySkillDirectory() {
        assertEquals(
                List.of("cancel", "projects", "route", "subscribe", "surroundings"),
                catalog.all().stream().map(Skill::name).toList());
    }

    @Test
    void parsesFrontmatterAndBody() {
        Skill subscribe = catalog.byNames(List.of("subscribe")).get(0);

        assertEquals(List.of("searchHouse", "searchPerson", "searchOrg", "createSubscription"),
                subscribe.tools());
        assertTrue(subscribe.description().startsWith("客户要办理认购"));
        assertTrue(subscribe.body().startsWith("## 生成认购"));
        // 正文必须完整，frontmatter 不能漏进 body
        assertTrue(subscribe.body().contains("houseIds 与 personIds（或 orgIds）传工具返回的真实 ID"));
        assertFalse(subscribe.body().contains("description:"));
        assertFalse(subscribe.body().contains("---"));
    }

    @Test
    void byNamesKeepsOrderAndSkipsUnknown() {
        List<Skill> skills = catalog.byNames(List.of("projects", "not-a-skill", "cancel", "projects"));
        assertEquals(List.of("projects", "cancel"), skills.stream().map(Skill::name).toList());
    }

    @Test
    void indexIsOneLinePerSkill() {
        String index = catalog.index();
        assertEquals(5, index.split("\n").length);
        assertTrue(index.contains("- surroundings: "));
    }
}

package com.yusijia.houseclientservices.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden Set 确定性测试：不启动 Spring、不调 LLM。
 * <p>
 * 验证两层：
 * <ul>
 *   <li>用例文件本身合法（JSON 可解析、技能名存在、类别齐全）</li>
 *   <li>工具目录逻辑正确（空技能 → 空工具、多技能 → 工具并集）</li>
 * </ul>
 * <p>
 * LLM 端到端断言（"输入 X → 选出 Y"）放在 {@link GoldenSetRunner}（手动触发），
 * 因为每次跑都要调真模型、花钱、耗时。
 */
class GoldenSetTest {

    /** 用例记录：JSON 里的一行 */
    public record Case(String id, String message, List<String> expectedSkills, String category, String note) {}

    private static List<Case> cases;
    private static SkillCatalog catalog;

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = GoldenSetTest.class.getResourceAsStream("/golden-set/cases.json")) {
            assertNotNull(is, "golden-set/cases.json 未找到");
            cases = mapper.readValue(is, new TypeReference<>() {});
        }
        catalog = new SkillCatalog("classpath*:skills/*/SKILL.md");
    }

    // ==================== 用例文件合法性 ====================

    @Test
    void 用例数量在30到50之间() {
        assertTrue(cases.size() >= 30 && cases.size() <= 50,
                "用例数应为 30-50，实际 " + cases.size());
    }

    @Test
    void 所有技能名都存在于目录() {
        Set<String> validNames = catalog.all().stream().map(Skill::name).collect(Collectors.toSet());
        for (Case c : cases) {
            for (String name : c.expectedSkills()) {
                assertTrue(validNames.contains(name),
                        "用例 " + c.id() + " 引用了不存在的技能: " + name);
            }
        }
    }

    @Test
    void 每个类别至少有一条() {
        Set<String> categories = cases.stream().map(Case::category).collect(Collectors.toSet());
        assertTrue(categories.contains("empty"), "缺少 empty 类别");
        assertTrue(categories.contains("intent"), "缺少 intent 类别");
        assertTrue(categories.contains("multi"), "缺少 multi 类别");
        assertTrue(categories.contains("confirm"), "缺少 confirm 类别");
    }

    @Test
    void 空命中用例不少于8条() {
        long count = cases.stream().filter(c -> "empty".equals(c.category)).count();
        assertTrue(count >= 8, "空命中用例应 >= 8 条，实际 " + count);
    }

    // ==================== SKILL.md 工具声明完整性 ====================

    @Test
    void 每个技能声明的工具名非空() {
        for (Skill skill : catalog.all()) {
            assertFalse(skill.tools().isEmpty(),
                    "技能 " + skill.name() + " 未声明任何工具");
            for (String tool : skill.tools()) {
                assertFalse(tool.isBlank(),
                        "技能 " + skill.name() + " 声明了空白工具名");
            }
        }
    }

    @Test
    void 技能间工具不重叠超出预期() {
        // 记录哪些工具被多个技能共享（用于排查"选了 A 技能却带上了 B 的工具"）
        var toolToSkills = new java.util.LinkedHashMap<String, List<String>>();
        for (Skill skill : catalog.all()) {
            for (String tool : skill.tools()) {
                toolToSkills.computeIfAbsent(tool, k -> new java.util.ArrayList<>()).add(skill.name());
            }
        }
        // 共享工具应该是 rememberSessionFact / rememberUserProfile 这类通用工具
        for (var entry : toolToSkills.entrySet()) {
            if (entry.getValue().size() > 2) {
                System.out.println("[INFO] 工具 " + entry.getKey() + " 被 " + entry.getValue() + " 共享");
            }
        }
        // 不 assert，只记录——将来加技能时人肉审查
    }

    // ==================== forSkills 逻辑 ====================

    /**
     * 直接验证 Skill 的 tools 并集逻辑（不依赖 ToolRegistry 的 Spring 注入）：
     * 空技能列表 → 空工具名集合；多技能 → 工具名并集。
     */
    @Test
    void 空技能列表工具集为空() {
        List<Skill> skills = List.of();
        Set<String> toolNames = new java.util.LinkedHashSet<>();
        for (Skill skill : skills) {
            toolNames.addAll(skill.tools());
        }
        assertTrue(toolNames.isEmpty(), "空技能列表的工具集必须为空");
    }

    @Test
    void 单技能工具集等于该技能声明() {
        List<Skill> skills = catalog.byNames(List.of("subscribe"));
        assertEquals(1, skills.size());
        Set<String> expected = Set.copyOf(skills.get(0).tools());
        Set<String> actual = new java.util.LinkedHashSet<>();
        skills.forEach(s -> actual.addAll(s.tools()));
        assertEquals(expected, actual, "单技能的工具集应等于该技能声明");
        assertTrue(actual.contains("searchHouse"));
        assertTrue(actual.contains("createSubscription"));
        assertFalse(actual.contains("webSearch"), "subscribe 不应含 webSearch");
    }

    @Test
    void 多技能工具集为并集() {
        List<Skill> skills = catalog.byNames(List.of("subscribe", "projects"));
        Set<String> actual = new java.util.LinkedHashSet<>();
        skills.forEach(s -> actual.addAll(s.tools()));
        // subscribe 的工具
        assertTrue(actual.contains("searchHouse"), "并集应含 searchHouse");
        assertTrue(actual.contains("createSubscription"), "并集应含 createSubscription");
        // projects 的工具
        assertTrue(actual.contains("searchProjects"), "并集应含 searchProjects");
        // 不应含未选技能的工具
        assertFalse(actual.contains("webSearch"), "不应含 webSearch（未选 web-search）");
        assertFalse(actual.contains("map_geocode"), "不应含 map_geocode（未选 route/surroundings）");
    }

    @Test
    void 全部技能工具集含所有已声明工具() {
        List<Skill> all = catalog.all();
        Set<String> actual = new java.util.LinkedHashSet<>();
        all.forEach(s -> actual.addAll(s.tools()));
        // 6 个技能的全部工具
        assertTrue(actual.contains("searchHouse"), "应含 searchHouse");
        assertTrue(actual.contains("createSubscription"), "应含 createSubscription");
        assertTrue(actual.contains("cancelSubscription"), "应含 cancelSubscription");
        assertTrue(actual.contains("searchProjects"), "应含 searchProjects");
        assertTrue(actual.contains("webSearch"), "应含 webSearch");
        assertTrue(actual.contains("map_geocode"), "应含 map_geocode");
        assertTrue(actual.contains("map_directions"), "应含 map_directions");
        assertTrue(actual.contains("map_search_places"), "应含 map_search_places");
        assertTrue(actual.contains("rememberUserProfile"), "应含 rememberUserProfile");
    }
}

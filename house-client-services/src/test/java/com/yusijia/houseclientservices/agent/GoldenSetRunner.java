package com.yusijia.houseclientservices.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

/**
 * Golden Set 端到端 Runner：调真 LLM 验证技能选择。
 * <p>
 * 手动触发（不在 CI 里跑）：
 * <pre>
 * mvn test -Dtest=GoldenSetRunner -Dgolden.set.model=qwen-plus
 * </pre>
 * <p>
 * 每次跑 40 条用例，约 1-2 分钟，花费约 ¥0.5-1。
 * 输出 pass rate 和每条用例的命中/未命中详情。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "agent.skill-selector.model=${golden.set.model:qwen-plus}"
})
class GoldenSetRunner {

    @Autowired
    private SkillSelector skillSelector;

    @Autowired
    private SkillCatalog skillCatalog;

    @Test
    void runGoldenSet() {
        List<GoldenSetTest.Case> cases = loadCases();
        int total = cases.size();
        int passed = 0;
        StringBuilder report = new StringBuilder();
        report.append("\n========== Golden Set Report ==========\n");
        report.append("Model: skillSelectorChatClient\n");
        report.append("Total: ").append(total).append("\n");
        report.append("-------------------------------\n");

        for (GoldenSetTest.Case c : cases) {
            // 每条用例用独立 conversationId，避免粘性选择干扰
            String cid = "golden-" + c.id();
            List<Skill> selected = skillSelector.select(cid, c.message());
            List<String> selectedNames = selected.stream().map(Skill::name).toList();

            // 比较：expectedSkills 是"应包含的技能"，不要求完全相等（多技能时可能多命中）
            // 空命中用例要求精确为空
            boolean ok;
            if (c.expectedSkills().isEmpty()) {
                ok = selectedNames.isEmpty();
            } else {
                // 至少包含所有期望技能（允许多命中，因为"宁多勿漏"）
                ok = selectedNames.containsAll(c.expectedSkills());
            }

            if (ok) {
                passed++;
                report.append("[PASS] ").append(c.id()).append(" | ")
                        .append(truncate(c.message(), 30)).append(" → ")
                        .append(selectedNames).append("\n");
            } else {
                report.append("[FAIL] ").append(c.id()).append(" | ")
                        .append(truncate(c.message(), 30)).append("\n")
                        .append("       expected: ").append(c.expectedSkills())
                        .append("  actual: ").append(selectedNames).append("\n");
            }
        }

        report.append("-------------------------------\n");
        report.append("Pass rate: ").append(passed).append("/").append(total)
                .append(" (").append(String.format("%.1f", passed * 100.0 / total)).append("%)\n");
        report.append("===================================\n");

        System.out.println(report);

        // 断言 pass rate >= 70%（基线；将来提高到 85%）
        org.junit.jupiter.api.Assertions.assertTrue(
                (double) passed / total >= 0.70,
                "Pass rate " + passed + "/" + total + " 低于基线 70%");
    }

    private List<GoldenSetTest.Case> loadCases() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try (java.io.InputStream is = GoldenSetRunner.class.getResourceAsStream("/golden-set/cases.json")) {
                if (is == null) throw new IllegalStateException("cases.json not found");
                return mapper.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load golden set cases", e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

package com.yusijia.houseclientservices.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索规划器的输出解析。规划器本身要调模型，不便单测；
 * 但"模型输出什么都要能安全解析"这件事必须钉住，因为它是整条检索链路的入口。
 */
class RetrievalPlannerTest {

    @Test
    void parsesStandardJson() {
        String raw = """
                {"queries":["江苏共创人造草坪 招聘","江苏共创人造草坪 外包 直签","共创草坪 Java 开发 薪资"]}""";
        assertEquals(
                List.of("江苏共创人造草坪 招聘", "江苏共创人造草坪 外包 直签", "共创草坪 Java 开发 薪资"),
                RetrievalPlanner.parseQueries(raw, 3));
    }

    @Test
    void toleratesCodeFenceAndProse() {
        String raw = """
                好的，我拆成三条：
                ```json
                ["南京首套房贷款利率 2026", "南京 契税 首套"]
                ```
                希望对你有帮助。"?""";
        assertEquals(
                List.of("南京首套房贷款利率 2026", "南京 契税 首套"),
                RetrievalPlanner.parseQueries(raw, 3));
    }

    @Test
    void stripsNumberingAndQuotes() {
        String raw = """
                ["1. 南京 公积金 利率", "- 南京 商贷 利率", "“南京 首付 比例”"]""";
        assertEquals(
                List.of("南京 公积金 利率", "南京 商贷 利率", "南京 首付 比例"),
                RetrievalPlanner.parseQueries(raw, 3));
    }

    @Test
    void capsQueryCountAndDedupes() {
        String raw = """
                ["A 招聘","a 招聘","B 招聘","C 招聘","D 招聘"]""";
        // 大小写不同视为同一条；总数被 maxQueries 截断
        assertEquals(List.of("A 招聘", "B 招聘"), RetrievalPlanner.parseQueries(raw, 2));
    }

    @Test
    void returnsEmptyForUnusableOutput() {
        assertTrue(RetrievalPlanner.parseQueries("", 3).isEmpty());
        assertTrue(RetrievalPlanner.parseQueries(null, 3).isEmpty());
        assertTrue(RetrievalPlanner.parseQueries("我无法回答这个问题。", 3).isEmpty());
        assertTrue(RetrievalPlanner.parseQueries("[]", 3).isEmpty());
        assertTrue(RetrievalPlanner.parseQueries("{\"queries\":\"坏了\"}", 3).isEmpty());
    }

    @Test
    void truncatesOverlongQuery() {
        String raw = "[\"" + "南".repeat(120) + "\"]";
        List<String> queries = RetrievalPlanner.parseQueries(raw, 3);
        assertEquals(1, queries.size());
        assertEquals(60, queries.get(0).length());
    }
}

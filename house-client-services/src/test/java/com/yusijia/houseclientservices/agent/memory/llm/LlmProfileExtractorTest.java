package com.yusijia.houseclientservices.agent.memory.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmProfileExtractor.parseJson 纯函数测试：
 * 验证各种模型输出格式的容错解析。
 */
class LlmProfileExtractorTest {

    @Test
    void 标准JSON数组() {
        String json = "[{\"key\":\"budget\",\"value\":\"300万左右\"},{\"key\":\"districtPref\",\"value\":\"江宁区\"}]";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(2, result.size());
        assertEquals("300万左右", result.get("budget"));
        assertEquals("江宁区", result.get("districtPref"));
    }

    @Test
    void 空数组() {
        Map<String, String> result = LlmProfileExtractor.parseJson("[]");
        assertTrue(result.isEmpty());
    }

    @Test
    void 前后有多余文字() {
        String json = "根据对话，我提取到以下偏好：\n[{\"key\":\"purpose\",\"value\":\"自住\"}]\n希望对你有帮助。";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(1, result.size());
        assertEquals("自住", result.get("purpose"));
    }

    @Test
    void Markdown代码块包裹() {
        String json = "```json\n[{\"key\":\"roomPref\",\"value\":\"三室两厅\"}]\n```";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(1, result.size());
        assertEquals("三室两厅", result.get("roomPref"));
    }

    @Test
    void 非法字段被过滤() {
        String json = "[{\"key\":\"hacker\",\"value\":\"injected\"},{\"key\":\"budget\",\"value\":\"200万\"}]";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(1, result.size());
        assertTrue(!result.containsKey("hacker"));
        assertEquals("200万", result.get("budget"));
    }

    @Test
    void 空值被跳过() {
        String json = "[{\"key\":\"budget\",\"value\":\"\"},{\"key\":\"purpose\",\"value\":\"投资\"}]";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(1, result.size());
        assertEquals("投资", result.get("purpose"));
    }

    @Test
    void 完全无法解析() {
        Map<String, String> result = LlmProfileExtractor.parseJson("我不知道该输出什么");
        assertTrue(result.isEmpty());
    }

    @Test
    void null和空串() {
        assertTrue(LlmProfileExtractor.parseJson(null).isEmpty());
        assertTrue(LlmProfileExtractor.parseJson("").isEmpty());
        assertTrue(LlmProfileExtractor.parseJson("   ").isEmpty());
    }

    @Test
    void 五个字段全出现() {
        String json = "[{\"key\":\"budget\",\"value\":\"300万\"},{\"key\":\"districtPref\",\"value\":\"江宁区\"}," +
                "{\"key\":\"roomPref\",\"value\":\"三室\"},{\"key\":\"purpose\",\"value\":\"学区\"},{\"key\":\"other\",\"value\":\"要朝南\"}]";
        Map<String, String> result = LlmProfileExtractor.parseJson(json);
        assertEquals(5, result.size());
    }
}

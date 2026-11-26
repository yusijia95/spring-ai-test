package com.yusijia.houseclientservices.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 只测标题生成的纯函数部分：{@code ensureTitle} 的写库语义由条件 UPDATE 保证，
 * 需要真实 MySQL，放到端到端验证里覆盖。
 */
class ConversationServiceTest {

    @Test
    void 短消息原样作为标题() {
        assertEquals("我想给1幢101办认购", ConversationService.titleOf("我想给1幢101办认购"));
    }

    @Test
    void 换行与连续空白折叠成单空格() {
        assertEquals("你好 我想看房", ConversationService.titleOf("  你好\n\n我想看房  "));
    }

    @Test
    void 超过24个字截断并补省略号() {
        String message = "一二三四五六七八九十一二三四五六七八九十一二三四五";
        // 25 个字：保留前 24 个 + 省略号
        assertEquals("一二三四五六七八九十一二三四五六七八九十一二三四" + "…", ConversationService.titleOf(message));
    }

    @Test
    void 恰好24个字不截断() {
        String message = "一二三四五六七八九十一二三四五六七八九十一二三四";
        assertEquals(message, ConversationService.titleOf(message));
    }

    @Test
    void 空白消息返回null() {
        assertNull(ConversationService.titleOf("   \n\t "));
        assertNull(ConversationService.titleOf(null));
        assertNull(ConversationService.titleOf(""));
    }
}

package com.yusijia.houseclientservices.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link PiiMasker} 的三条掩码规则与"宁可漏也不误伤"的边界。
 */
class PiiMaskerTest {

    @Test
    void 身份证_末位是数字() {
        assertEquals("证件号 330102********1234 已登记", PiiMasker.mask("证件号 330102199001011234 已登记"));
    }

    @Test
    void 身份证_末位是X() {
        assertEquals("身份证330102********123X", PiiMasker.mask("身份证33010219900101123X"));
    }

    @Test
    void 手机号_中间四位打码() {
        assertEquals("联系电话 138****5678 已记录", PiiMasker.mask("联系电话 13812345678 已记录"));
    }

    @Test
    void 结构化手机号字段按同一口径掩码() {
        // 接口与工具返回的是裸手机号，不是整段文本；掩码口径必须和日志侧一致
        assertEquals("138****0001", PiiMasker.maskPhone("13800000001"));
        assertNull(PiiMasker.maskPhone(null));
        // 非手机号格式原样返回：宁可漏也不误伤，由调用方决定是否允许明文
        assertEquals("0571-88886666", PiiMasker.maskPhone("0571-88886666"));
    }

    @Test
    void 银行卡_16位() {
        // 16 位银行卡：6222 + 000012345678 + 1234？不对，16 = 4 + 8 + 4
        // 用 6222000000001234 不够 16 位（只有 16 字符），改用 16 位真实形态
        assertEquals("卡号 6222********1234 绑定成功",
                PiiMasker.mask("卡号 6222000000001234 绑定成功"));
    }

    @Test
    void 银行卡_19位() {
        // 19 位：4 + 11 + 4
        assertEquals("卡号 6222***********9876 绑定成功",
                PiiMasker.mask("卡号 6222000000012349876 绑定成功"));
    }

    @Test
    void 业务单号_带字母前缀不被误伤() {
        // RZ 开头的认购单号：两侧紧邻字母，规则应跳过
        assertEquals("单号 RZ2117917689579966465 已生成",
                PiiMasker.mask("单号 RZ2117917689579966465 已生成"));
    }

    @Test
    void 房号_金额_日期不被误伤() {
        String text = "1幢101室，总价 2850000 元，2026-11-20 签约，房号 1203";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void null与空串原样返回() {
        assertNull(PiiMasker.mask(null));
        assertEquals("", PiiMasker.mask(""));
    }

    @Test
    void 无PII的普通中文句子原样返回() {
        String text = "好的，我帮您查一下这个楼盘的价格。";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void 先身份证后手机号_顺序正确() {
        // 同句里身份证和手机号并存，身份证先整段遮掉，手机号不被拆坏
        assertEquals("330102********1234 / 138****5678",
                PiiMasker.mask("330102199001011234 / 13812345678"));
    }

    @Test
    void 截断_短文本不动() {
        assertEquals("短文本", PiiMasker.truncate("短文本", 10));
        assertEquals("刚好", PiiMasker.truncate("刚好", 2));
    }

    @Test
    void 截断_超长文本截断并带原长提示() {
        String longText = "x".repeat(100);
        String result = PiiMasker.truncate(longText, 50);
        assertTrue(result.startsWith("x".repeat(50)));
        assertTrue(result.endsWith("（已截断，原长 100）"));
    }

    @Test
    void 截断_max小于等于0不截断() {
        assertEquals("abcdef", PiiMasker.truncate("abcdef", 0));
        assertEquals("abcdef", PiiMasker.truncate("abcdef", -1));
    }
}

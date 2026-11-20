package com.yusijia.houseclientservices.agent;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志用 PII 脱敏（纯函数，无状态，可单测）。
 * <p>
 * 只处理三类"正则能可靠识别"的形态，宁可漏也不要误伤：
 * <ol>
 *   <li>18 位身份证（末位可为 X/x）：保留前 6 位 + 后 4 位，如 {@code 330102********1234}；</li>
 *   <li>11 位手机号（{@code 1[3-9]} 开头）：如 {@code 138****5678}；</li>
 *   <li>16–19 位连续数字（银行卡 / 长编号）：保留前 4 后 4。</li>
 * </ol>
 * 三类都要求两侧不是字母数字，所以 {@code RZ2117917689579966465} 这类带字母前缀的业务单号、
 * 房号（{@code 1幢101}）、金额、日期都不会被改成掩码。
 * <p>
 * 姓名、地址这类无法用正则判定的 PII 不在这里处理：调用方要么不打印，要么只打印结构化字段
 * （如客户 ID、房号），不打印自由文本。见 {@link MaskingLoggingAdvisor}。
 */
public final class PiiMasker {

    /** 18 位身份证：6 位地址码 + 8 位生日 + 3 位顺序码 + 1 位校验位（可为 X） */
    private static final Pattern ID_CARD_18 =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]{6})([0-9]{8})([0-9]{3}[0-9Xx])(?![0-9A-Za-z])");

    /** 11 位手机号：1 开头，第二位 3–9 */
    private static final Pattern MOBILE_11 =
            Pattern.compile("(?<![0-9A-Za-z])(1[3-9][0-9])([0-9]{4})([0-9]{4})(?![0-9A-Za-z])");

    /** 16–19 位连续数字：银行卡、长流水号（中间段 8–11 位，总长正好覆盖 16–19 位） */
    private static final Pattern LONG_NUMBER =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]{4})([0-9]{8,11})([0-9]{4})(?![0-9A-Za-z])");

    /**
     * 15 位连续数字：老式银行卡、短流水号。放在 LONG_NUMBER 之后单独处理，
     * 因为 LONG_NUMBER 的中间段最少 8 位、总长最少 16 位，盖不住 15 位。
     */
    private static final Pattern LONG_NUMBER_15 =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]{4})([0-9]{7})([0-9]{4})(?![0-9A-Za-z])");

    private PiiMasker() {
    }

    /**
     * 遮罩文本里的证件号、手机号、长数字账号。
     * <p>
     * 顺序固定：身份证 → 手机号 → 长数字。先按 18 位整段处理身份证，
     * 已遮罩的部分不含连续数字，后面的规则就不会把它的片段再切一刀。
     *
     * @param text 原始文本，null / 空串原样返回
     * @return 脱敏后的文本
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = replace(text, ID_CARD_18, m -> m.group(1) + "*".repeat(8) + m.group(3));
        masked = replace(masked, MOBILE_11, m -> m.group(1) + "****" + m.group(3));
        masked = replace(masked, LONG_NUMBER, m -> m.group(1) + "*".repeat(m.group(2).length()) + m.group(3));
        return replace(masked, LONG_NUMBER_15, m -> m.group(1) + "*".repeat(7) + m.group(3));
    }

    /**
     * 超长文本截断，并在末尾标注原始长度。
     *
     * @param text 原始文本，null 原样返回
     * @param max  保留的最大字符数；小于等于 0 视为不截断
     * @return 未超长时原样返回，超长时截断并追加 {@code ...（已截断，原长 N）}
     */
    public static String truncate(String text, int max) {
        if (text == null || max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...（已截断，原长 " + text.length() + "）";
    }

    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            // quoteReplacement：正文里的 $ / \ 不能被当成替换模板
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

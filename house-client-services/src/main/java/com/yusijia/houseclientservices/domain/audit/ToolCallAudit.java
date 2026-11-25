package com.yusijia.houseclientservices.domain.audit;

/**
 * 一次工具调用的审计记录（P0-5）。
 *
 * @param tool       工具名
 * @param args       原始入参 JSON（落库前脱敏）
 * @param result     原始返回值（落库前脱敏）
 * @param durationMs 本次调用耗时（毫秒）
 * @param failed     调用是否抛异常（异常信息已并入 result）
 */
public record ToolCallAudit(String tool, String args, String result, long durationMs, boolean failed) {
}

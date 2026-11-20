package com.yusijia.houseclientservices.security;

/**
 * 访问被拒（权限不足 / 会话归属不符）。
 * <p>
 * 由全局异常处理翻译成 403，并写一行审计日志。不直接抛 {@code BizException}：
 * 业务校验（如"房屋ID无效"）和权限拒绝是两类错误，前端与日志要区分对待。
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}

package com.yusijia.houseclientservices.exception;

/**
 * 业务校验异常，用于向调用方（含 AI 工具）返回可读的失败原因。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}

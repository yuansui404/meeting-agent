package com.meeting.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public BusinessException(String message) {
        this(100001, message);
    }

    public BusinessException(String message, Throwable cause) {
        this(100001, message, cause);
    }

    // --- 客户端错误 1xxxxx ---

    public static BusinessException notFound(String msg) {
        return new BusinessException(100002, msg);
    }

    // --- 外部服务错误 2xxxxx ---

    public static BusinessException timeout(String msg) {
        return new BusinessException(200001, msg);
    }

    public static BusinessException rateLimited(String msg) {
        return new BusinessException(200002, msg);
    }

    public static BusinessException externalError(String msg, Throwable cause) {
        return new BusinessException(200003, msg, cause);
    }

    // --- 业务逻辑错误 3xxxxx ---

    public static BusinessException processingFailed(String msg, Throwable cause) {
        return new BusinessException(300001, msg, cause);
    }
}

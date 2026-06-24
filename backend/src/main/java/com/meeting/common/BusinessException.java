package com.meeting.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(400, message);
    }

    public static BusinessException notFound(String msg) {
        return new BusinessException(404, msg);
    }

    public static BusinessException timeout(String msg) {
        return new BusinessException(408, msg);
    }

    public static BusinessException rateLimited(String msg) {
        return new BusinessException(429, msg);
    }
}

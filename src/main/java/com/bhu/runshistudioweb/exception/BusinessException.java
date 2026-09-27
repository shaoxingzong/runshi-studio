package com.bhu.runshistudioweb.exception;

import lombok.Getter;

/**
 * 继承自 RuntimeException 的自定义异常类，专门用于抛出与业务逻辑相关的错误
 * （如：用户已存在、余额不足等）。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 错误码
     */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}

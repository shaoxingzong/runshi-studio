package com.bhu.runshistudioweb.exception;

import lombok.Getter;

/**
 * 业务异常：继承 RuntimeException，专门表达「可预期的业务失败」 （用户已存在、参数不合法、无权限等）。
 *
 * @author shaoshing
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码，取值来自 ErrorCode */
    private final String code;

    /**
     * 直接用状态码 + 提示信息构造。推荐优先使用入参为 ErrorCode 的重载，避免手写数字
     *
     * @param code 业务状态码
     * @param message 提示信息（会直接返回给前端）
     */
    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 用错误码构造：code 与 message 都取自枚举，两者天然一致
     *
     * @param errorCode 错误码枚举
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 用错误码构造，但覆盖提示信息。典型场景：同一个 NO_AUTH_ERROR，需要告诉用户具体是哪种权限不足
     *
     * @param errorCode 错误码枚举，决定 code
     * @param message 覆盖枚举默认提示的信息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}

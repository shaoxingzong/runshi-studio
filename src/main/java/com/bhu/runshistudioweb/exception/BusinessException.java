package com.bhu.runshistudioweb.exception;

import lombok.Getter;

// 业务异常：继承自 RuntimeException，专门用于表达「可预期的业务失败」
// （如：用户已存在、参数不合法、无权限等）。
//
// 设计要点：
// - 继承 RuntimeException 而非 Exception：业务校验失败属于预期内分支，
// 不需要每一层都写 try-catch，也不该污染方法签名（无需 throws 声明）；
// - 携带 code：由 GlobalExceptionHandler 取出后原样返回给前端，
// 这样「抛异常」和「返回错误码」两件事就统一了，业务代码只管抛，不用层层往回传错误码；
// - 该异常会被全局异常处理器捕获并原样透传 code 与 message，
// 所以 message 必须是能给用户看的文案，不能塞 SQL、堆栈等内部细节。
//
// 注意：如果只是想「条件成立就抛异常」，业务代码里更推荐用
// ThrowUtils#throwIf，可少写一个 if 块。
@Getter
public class BusinessException extends RuntimeException {

    // 业务状态码，取值来自 ErrorCode
    //
    // 类型是 String：错误码是 5 位字符串（来源 A/B/C + 4 位编号），见 ErrorCode。
    private final String code;

    // 直接用状态码 + 提示信息构造
    // 推荐优先使用入参为 ErrorCode 的重载，避免手写数字
    //
    // @param code 业务状态码
    // @param message 提示信息（会直接返回给前端）
    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    // 用错误码构造：code 与 message 都取自枚举，两者天然一致
    //
    // @param errorCode 错误码枚举
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    // 用错误码构造，但覆盖提示信息
    // 典型场景：同一个 NO_AUTH_ERROR，需要告诉用户具体是哪种权限不足
    //
    // @param errorCode 错误码枚举，决定 code
    // @param message 覆盖枚举默认提示的信息
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}

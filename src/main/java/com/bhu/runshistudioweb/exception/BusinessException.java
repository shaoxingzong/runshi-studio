package com.bhu.runshistudioweb.exception;

import lombok.Getter;

/**
 * 业务异常：继承自 RuntimeException，专门用于表达「可预期的业务失败」
 * （如：用户已存在、参数不合法、无权限等）。
 *
 * author: shaoshing
 *
 * <p>设计要点：
 * <ul>
 *     <li>继承 {@link RuntimeException} 而非 Exception：业务校验失败属于预期内分支，
 *     不需要每一层都写 try-catch，也不该污染方法签名（无需 throws 声明）；</li>
 *     <li>携带 {@code code}：由 {@code GlobalExceptionHandler} 取出后原样返回给前端，
 *     这样「抛异常」和「返回错误码」两件事就统一了，业务代码只管抛，不用层层往回传错误码；</li>
 *     <li>该异常会被全局异常处理器捕获并**原样透传** code 与 message，
 *     所以 message 必须是能给用户看的文案，不能塞 SQL、堆栈等内部细节。</li>
 * </ul>
 *
 * <p><b>注意</b>：如果只是想「条件成立就抛异常」，业务代码里更推荐用
 * {@link ThrowUtils#throwIf(boolean, ErrorCode, String)}，可少写一个 if 块。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 业务状态码，取值来自 {@link ErrorCode}
     *
     * <p>类型是 <b>String</b>：错误码是 5 位字符串（来源 A/B/C + 4 位编号），见 {@link ErrorCode}。
     */
    private final String code;

    /**
     * 直接用状态码 + 提示信息构造
     * 推荐优先使用入参为 {@link ErrorCode} 的重载，避免手写数字
     *
     * @param code    业务状态码
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
     * 用错误码构造，但覆盖提示信息
     * 典型场景：同一个 NO_AUTH_ERROR，需要告诉用户具体是哪种权限不足
     *
     * @param errorCode 错误码枚举，决定 code
     * @param message   覆盖枚举默认提示的信息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}

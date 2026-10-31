package com.bhu.runshistudioweb.exception;

/**
 * 抛异常工具类
 *
 * @author shaoshing
 */
public class ThrowUtils {

    /**
     * 条件成立就抛出调用方自己准备好的异常实例
     *
     * @param condition 判定条件，为 true 时抛出；传 false 时本方法等价于空操作
     * @param runtimeException 待抛出的异常实例，不能为 null——传 null 会在 throw 那一行抛 NullPointerException，掩盖真正的错误原因
     * @throws RuntimeException 当 condition 为 true 时，原样抛出传入的实例（不做包装）
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        // 判断条件：false 时直接往下走，本方法等价于空操作
        if (condition) {
            // 原样抛出传入的实例，不做包装，调用方拼好的提示不会被改写
            throw runtimeException;
        }
    }

    /**
     * 条件成立就抛出业务异常，提示文案用错误码自带的默认值
     *
     * @param condition 判定条件，为 true 时抛出
     * @param errorCode 错误码枚举，同时决定响应里的 code 与 message，不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 当 condition 为 true 时抛出，其 code 与 message 取自 errorCode
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /**
     * 条件成立就抛出业务异常，并用自己的提示覆盖错误码的默认文案
     *
     * @param condition 判定条件，为 true 时抛出
     * @param errorCode 错误码枚举，只决定响应里的 code；其默认文案会被 message 覆盖，不能为 null
     * @param message 覆盖默认提示的文案，必须是能给最终用户看的完整句子，不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 当 condition 为 true 时抛出，其 code 取自 errorCode、message 取自本参数
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}

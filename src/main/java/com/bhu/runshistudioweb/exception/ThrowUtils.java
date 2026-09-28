package com.bhu.runshistudioweb.exception;

/**
 * 抛异常工具类
 *
 * author: shaoshing
 *
 * <p>为什么需要它：业务代码里大量出现「条件成立就报错」的写法。手写 if 块会让校验逻辑
 * 占用大量行数，把真正的业务动作淹没。用本工具类可以压成一行：
 *
 * <pre>{@code
 * // 之前
 * if (user == null) {
 *     throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
 * }
 *
 * // 现在
 * ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
 * }</pre>
 *
 * <p><b>注意</b>：这是「断言式」写法，只适合表达「不满足前置条件就直接失败」。
 * 如果条件成立后还要做别的事情，请老老实实写 if 块，不要为了短而牺牲可读性。
 */
public class ThrowUtils {

    /**
     * 条件成立则抛出指定异常
     *
     * @param condition        条件，为 true 时抛出异常
     * @param runtimeException 待抛出的异常实例（调用方自己 new，便于携带自定义信息）
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * 条件成立则抛出业务异常（使用错误码默认提示）
     *
     * @param condition 条件，为 true 时抛出异常
     * @param errorCode 错误码枚举，同时决定 code 与 message
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /**
     * 条件成立则抛出业务异常（自定义提示信息）
     *
     * @param condition 条件，为 true 时抛出异常
     * @param errorCode 错误码枚举，决定 code
     * @param message   覆盖枚举默认提示的信息，需是能给用户看的文案
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}

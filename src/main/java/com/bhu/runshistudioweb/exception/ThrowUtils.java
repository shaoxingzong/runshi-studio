package com.bhu.runshistudioweb.exception;

/**
 * 抛异常工具类
 *
 * 业务代码里大量出现「条件成立就报错」的写法，手写 if 块会让校验逻辑占用大量行数，
 * 把真正的业务动作淹没。用本工具类可以压成一行：
 *
 * @author shaoshing
 */
public class ThrowUtils {

    /**
     * 条件成立就抛出调用方自己准备好的异常实例
     *
     * 这是最底层的重载，另外两个最终都调它，因此「判条件、抛异常」全项目只有这一份实现。
     * 接受已 new 好的异常对象而非异常类型，是为了让调用方能携带任意自定义信息
     * （拼入非法取值、把原始异常包成 cause），这类需求用「错误码 + 固定文案」表达不了。
     *
     * 坑：异常实例在调用点就已构造，即使条件为 false 也不会抛出，这次构造照样发生。
     * 不要传构造代价高、或构造时会做 IO / 打日志的异常；需要惰性构造时先用 if 判断再 new。
     *
     * @param condition 判定条件，为 true 时抛出；传 false 时本方法等价于空操作
     * @param runtimeException 待抛出的异常实例，不能为 null——传 null 会在
     *                         throw 那一行抛 NullPointerException，掩盖真正的错误原因
     * @throws RuntimeException 当 condition 为 true 时，原样抛出传入的实例（不做包装）
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        // ① 判断条件：false 时直接往下走，本方法等价于空操作
        if (condition) {
            // ② 原样抛出传入的实例，不做包装，调用方拼好的提示不会被改写
            throw runtimeException;
        }
    }

    /**
     * 条件成立就抛出业务异常，提示文案用错误码自带的默认值
     *
     * 绝大多数校验的提示语是通用的（「未登录」「无权限」），已经写在 ErrorCode 里，
     * 调用点再抄一遍就会出现两处定义、改一处漏一处。错误原因不需要补充细节时用它。
     *
     * @param condition 判定条件，为 true 时抛出
     * @param errorCode 错误码枚举，同时决定响应里的 code 与 message，不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 当 condition 为 true 时抛出，
     * 其 code 与 message 取自 errorCode
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /**
     * 条件成立就抛出业务异常，并用自己的提示覆盖错误码的默认文案
     *
     * 同一个错误码在不同场景下需要的提示粒度不同。「请求参数错误」说不清到底是哪个参数错了，
     * 用户看到这四个字基本无法自助解决。本重载保留 code（前端仍能按 code 归类处理），
     * 只替换 message，例如「审核状态只能是 0（待审）/ 1（已通过）/ 2（已驳回）」。
     *
     * 坑：message 会原样返回给前端并展示给用户，绝不能拼进异常堆栈、SQL、表名、内部类名。
     * 需要记录这些细节时写日志，不要写进 message。
     *
     * @param condition 判定条件，为 true 时抛出
     * @param errorCode 错误码枚举，只决定响应里的 code；其默认文案会被 message 覆盖，不能为 null
     * @param message 覆盖默认提示的文案，必须是能给最终用户看的完整句子，不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 当 condition 为 true 时抛出，
     * 其 code 取自 errorCode、message 取自本参数
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}

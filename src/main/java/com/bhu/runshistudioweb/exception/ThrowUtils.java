package com.bhu.runshistudioweb.exception;

/**
 * 抛异常工具类
 *
 * 为什么需要它：业务代码里大量出现「条件成立就报错」的写法。手写 if 块会让校验逻辑
 * 占用大量行数，把真正的业务动作淹没。用本工具类可以压成一行：
 *
 * // 手写 if 块（占用 3 行，把业务动作淹没）
 * if (user == null) {
 *     throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
 * }
 *
 * // 用本工具类（1 行，业务动作一眼可见）
 * ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
 *
 * @author shaoshing
 */
public class ThrowUtils {

    /**
     * 条件成立就抛出调用方自己准备好的异常实例
     *
     * <p>为什么这样设计：这是三个重载里最底层的一个，另外两个最终都调它，
     * 所以「判条件、抛异常」这套动作全项目只有这一份实现。它接受一个已经
     * new 好的异常对象而不是异常类型，是为了让调用方能携带任意自定义信息
     * （比如把非法取值拼进提示、把原始异常包成 cause）——这类需求用
     * 「错误码 + 固定文案」的重载表达不了，而为了它单独写一个 if 块，
     * 又会把校验代码重新摊开，失去本工具类的意义。
     *
     * <p>为什么参数顺序是「条件在前、异常在后」：读代码时最想先看到的是
     * 「什么情况下会炸」，条件写在第一个实参位置，扫一眼就知道这行在防什么；
     * 若反过来，视线会先落在冗长的 new 表达式上，条件被挤到行尾。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>判断 condition 是否为 true；</li>
     *     <li>为 true 时直接 throw 传入的实例，为 false 时什么也不做、正常返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：异常实例在调用点就已经 new 出来了，即使条件为 false、
     * 根本不会抛出，这次对象构造也已经发生。对 BusinessException 这种
     * 只存几个字段的轻量异常无所谓，但不要在这里传一个构造代价很高、
     * 或者构造时会做 IO / 打日志的异常——那等于每次校验都白跑一遍。
     * 需要惰性构造时，应当先用 if 判断再 new。
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
     * <p>为什么这样设计：绝大多数校验的提示语是通用的（「未登录」「无权限」
     * 「请求参数错误」），这些文案已经写在 ErrorCode 里，调用点再抄一遍
     * 就会出现两处定义、改一处漏一处。本重载让调用方只给错误码，
     * 文案与状态码都从枚举取，保证全站同一种错误提示完全一致。
     *
     * <p>什么时候用它：错误原因不需要补充细节时。例如
     * {@code throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR)}——
     * 「未登录」这三个字对用户已经足够，多写反而可能泄露内部信息。
     * 反过来，如果用户需要知道「到底哪个参数错了、合法值是什么」，
     * 就应当用带 message 的那个重载。
     *
     * <p>执行步骤（本方法只有一行，编号与代码标注一一对应）：
     * <ol>
     *     <li>用错误码构造 BusinessException 后立刻交给两参重载去判条件并抛出——
     *     把「怎么抛」集中在一处，将来若要在抛出前统一加日志或埋点，
     *     只改底层那个方法即可，三个重载不会各改一遍。</li>
     * </ol>
     *
     * <p>容易踩的坑：错误码只决定「返回给前端的 code 与默认文案」，
     * 它不会自动写日志。排查线上问题时，光靠这个提示往往定位不到具体是
     * 哪个请求、哪个字段出的问题，必要时仍需在调用点补一条日志。
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
     * <p>为什么这样设计：同一个错误码在不同场景下需要的提示粒度不同。
     * 「请求参数错误」这个默认文案说不清「到底是哪个参数错了、应该传什么」，
     * 而用户看到「参数错误」四个字基本无法自助解决。本重载保留错误码
     * （前端仍能按 code 归类处理），只替换 message，让提示具体到能直接照做，
     * 例如「审核状态只能是 0（待审）/ 1（已通过）/ 2（已驳回）」。
     *
     * <p>什么时候用它：错误分类明确、但需要补充细节时。判断标准很简单——
     * 如果用户看完提示还需要问「那我该传什么」，就该用这个重载。
     * 若提示里要列出全部合法取值，应当拼枚举的 valuesText()，
     * 而不是手写字符串，否则新增取值后提示会与现实脱节。
     *
     * <p>执行步骤（本方法只有一行，编号与代码标注一一对应）：
     * <ol>
     *     <li>用「错误码 + 自定义文案」构造 BusinessException，再交给两参重载去判条件并抛出，
     *     与其它重载保持完全相同的抛出路径。</li>
     * </ol>
     *
     * <p>容易踩的坑：message 会原样返回给前端并展示给用户，因此
     * 绝不能拼进异常堆栈、SQL、表名、内部类名这类实现细节——
     * 它们对用户毫无意义，却会泄露系统结构，是常见的信息泄露入口。
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

package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;

/**
 * 统一响应封装工具类
 *
 * 每个 Controller 手写 new BaseResponse<>(...) 既啰嗦又容易写错（最常见的是顺手把成功码
 * 写成 HTTP 的 200，那个错误不会编译失败，只会让前端拦截器把成功当成失败）。
 * 集中到工具类后，业务代码一行静态调用即可完成封装，成功码也只有一个来源。
 *
 * 约定：成功一律用 success，成功码固定 00000；失败必须使用 ErrorCode 中定义的号段
 * （A 用户端 / B 当前系统 / C 第三方服务），不要自己临时编号，否则前端没法按来源归类；
 * 失败响应的 data 固定为 null。
 *
 * @author shaoshing
 */
public class ResultUtils {

    /**
     * 把业务数据包装成成功响应
     *
     * 成功码只有这一处来源（ErrorCode.SUCCESS），各处手写的话改号段时必然漏改。
     * 用泛型方法而不是返回 Object，是为了让调用方的返回类型能被编译器校验。
     *
     * 坑：别把「操作失败」包成 success(false)，失败必须走 error，否则前端只按 code
     * 判断的拦截器会把失败当成功。
     *
     * @param data 业务数据，允许为 null（例如删除接口只关心成败，可以传 true 或 null）；
     *             泛型 T 由调用方推断，传错类型会在编译期报错
     * @param <T> 业务数据的类型
     * @return 成功响应，code 固定为 ErrorCode.SUCCESS 的取值、message 固定为它的默认文案
     */
    public static <T> BaseResponse<T> success(T data) {
        // ① 成功码取自枚举而不是手写 0：号段只有一处定义，改号段时不会漏改这里
        // ② data 原样放入：成功路径不对数据做任何加工或判空
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), data, ErrorCode.SUCCESS.getMessage());
    }

    /**
     * 把错误码包装成失败响应，提示用枚举自带的默认文案
     *
     * 这是失败响应最常用的入口。绝大多数提示语已经写在 ErrorCode 里，
     * 调用点再写一遍既啰嗦又容易与枚举不一致；让 code 与 message 都来自同一个枚举，
     * 保证「同一个错误码在任何接口上都是同一句话」。
     *
     * 返回类型用通配符 {@code BaseResponse<?>} 而不是泛型方法：失败响应里 data 恒为 null，
     * 没有类型信息可推断，做成泛型方法反而要求调用方每次显式指定类型参数。
     *
     * 坑：HTTP 状态码仍然是 200，失败只体现在 code 字段上。为了「更 RESTful」改成 4xx/5xx
     * 会让前端拦截器同时处理「HTTP 错误」和「业务错误」两套分支，更容易漏判。
     *
     * @param errorCode 错误码枚举，不能为 null；传 null 会在构造响应时 NPE
     * @return 失败响应，code 与 message 取自 errorCode，data 为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 用自定义状态码与提示构造失败响应
     *
     * 正常情况下错误码都应先加进 ErrorCode 枚举，前端才能按来源归类处理。
     * 本方法只给「只在某一个接口出现、不值得占用全局号段」的临时失败留出口，
     * 代价是调用方要自己保证码值不与已有号段冲突。
     *
     * 只要这个错误在前端需要被识别、需要按类型提示，就应该去 ErrorCode 加枚举值——
     * 散落的自定义码没有归属，是前端最难处理的一类。
     *
     * @param code 业务状态码，必须是 5 位字符串且不与 ErrorCode 已有取值冲突；
     *             传 null 不会立刻报错，但前端拿到 null 后无法做分支判断
     * @param message 给用户看的提示文案，不能包含异常堆栈、SQL、内部类名等实现细节
     * @return 失败响应，data 固定为 null
     */
    public static BaseResponse<?> error(String code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 用错误码 + 自定义提示构造失败响应
     *
     * 兼顾「前端能按 code 归类」与「提示足够具体」：只传错误码时提示是通用的「无权限」，
     * 用户不知道缺哪个权限；只传自定义码时前端又无法归类。不直接改枚举里的文案，
     * 是因为同一个错误码常被多处复用而各处原因不同，按调用点覆盖才是正确的粒度。
     *
     * 坑：message 会直接展示给最终用户，别写成技术性描述；也别与 code 语义矛盾——
     * 用 A0401 却提示「未登录」会让前端的分支判断与文案互相打架。
     *
     * @param errorCode 错误码枚举，只决定 code，不能为 null
     * @param message 覆盖枚举默认提示的文案，必须是能给用户看的完整句子
     * @return 失败响应，code 取自 errorCode、message 取自本参数，data 为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}

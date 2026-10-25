package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;

/**
 * 统一响应封装工具类
 *
 * 为什么需要它：如果每个 Controller 都手写 new BaseResponse<>(0, data, "ok")，
 * 既啰嗦又容易写错（比如习惯性地把成功码写成 HTTP 的 200）。集中到工具类后，
 * 业务代码一行静态调用即可完成封装，成功码也只有一个来源。
 *
 * 使用约定：
 * - 成功一律用 success，成功码固定为 00000；
 * - 失败必须使用 ErrorCode 中定义的号段（A 用户端 / B 当前系统 / C 第三方服务），
 * 不要自己临时编号，否则前端没法按来源归类处理；
 * - 失败响应的 data 固定为 null，前端拿到 code 不为 00000 时无需再看 data。
 *
 * @author shaoshing
 */
public class ResultUtils {

    /**
     * 把业务数据包装成成功响应
     *
     * <p>为什么这样设计：成功响应的三个字段（code / data / message）里，
     * 只有 data 是每个接口不同的。若每个 Controller 都手写
     * {@code new BaseResponse<>("00000", data, "ok")}，成功码就会被抄几百遍，
     * 将来改号段时必然漏改；更常见的错误是顺手把成功码写成 HTTP 的 200——
     * 那个错误不会编译失败，只会让前端拦截器把成功当成失败。
     * 集中到这里后，成功码只有一个来源（ErrorCode.SUCCESS），
     * 调用点也短到可以塞进 return 那一行。
     *
     * <p>为什么用泛型方法而不是返回 Object：泛型让调用方的返回类型
     * （如 {@code BaseResponse<Long>}）能被编译器校验，前端拿到的 JSON
     * 结构也更稳定；若返回 Object，Controller 方法签名就得跟着放宽，
     * 接口文档会退化成「什么都可能」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>从 ErrorCode.SUCCESS 取 code 与 message——不手写字符串，
     *     避免出现「有的接口写 00000、有的写 0」这种前端无法统一判断的局面；</li>
     *     <li>把 data 原样放进响应对象，成功路径不对数据做任何加工或判空。</li>
     * </ol>
     *
     * <p>容易踩的坑：data 传 null 时响应里依然会有 data 字段（值为 null），
     * 而不是字段消失——前端可以放心地写 {@code res.data.xxx} 之前先判 data，
     * 但要清楚「没有字段」和「字段为 null」在这里是同一件事。
     * 另外不要把「操作失败」也包成 success(false)，失败必须走 error，
     * 否则前端只按 code 判断的拦截器会把失败当成功。
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
     * <p>为什么这样设计：这是失败响应最常用的入口。绝大多数失败的提示语
     * 已经写在 ErrorCode 里（「未登录」「无权限」「请求数据不存在」），
     * 调用点再写一遍既啰嗦又容易与枚举不一致。让 code 与 message 都来自同一个枚举，
     * 保证「同一个错误码在任何接口上都是同一句话」。
     *
     * <p>为什么返回类型是通配符 {@code BaseResponse<?>} 而不是泛型方法：
     * 失败响应里 data 恒为 null，没有任何类型信息可推断；若做成泛型方法，
     * 调用方每次都得显式指定类型参数，反而更啰嗦。用通配符表示
     * 「这里的数据部分不重要」，语义上更准确。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>直接交给 BaseResponse 的 ErrorCode 构造器——code 与 message
     *     一次性从枚举取出，避免两处分别赋值时写岔；</li>
     *     <li>该构造器会把 data 固定为 null，因此这里不需要传数据。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的 HTTP 状态码仍然是 200，失败只体现在 code 字段上。
     * 如果为了「更 RESTful」把它改成 4xx/5xx，前端的统一拦截器就要同时处理
     * 「HTTP 错误」和「业务错误」两套分支，反而更容易漏判。
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
     * <p>为什么这样设计：正常情况下错误码都应当先加进 ErrorCode 枚举，
     * 这样前端才能按来源（A 用户端 / B 当前系统 / C 第三方）归类处理。
     * 但枚举是编译期固定的，总有一些「只在某一个接口里出现、且不值得
     * 占用一个全局号段」的临时失败。本方法给这种场景留一个出口，
     * 代价是调用方要自己保证码值不与已有号段冲突。
     *
     * <p>什么时候不该用它：只要这个错误在前端需要被识别、需要按类型提示，
     * 就应该去 ErrorCode 加一个枚举值。散落的自定义码是前端最难处理的一类，
     * 因为它们没有归属，无法归类。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>用调用方给的 code 与 message 构造响应；</li>
     *     <li>data 显式写 null——失败响应不该带数据，否则前端可能误用半成品数据。</li>
     * </ol>
     *
     * <p>容易踩的坑：message 会原样返回给前端并展示给用户，
     * 因此不能把异常堆栈、SQL 语句、内部类名拼进去。需要保留这些信息时写日志，
     * 不要图省事塞进响应体。
     *
     * @param code 业务状态码，必须是 5 位字符串且不与 ErrorCode 已有取值冲突；
     *             传 null 不会立刻报错，但前端拿到 null 后无法做分支判断
     * @param message 给用户看的提示文案，不能包含内部实现细节
     * @return 失败响应，data 固定为 null
     */
    public static BaseResponse<?> error(String code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 用错误码 + 自定义提示构造失败响应
     *
     * <p>为什么这样设计：它兼顾了「前端能按 code 归类」与「提示足够具体」两件事。
     * 只传错误码时提示是通用的「无权限」，用户不知道是缺哪个权限；
     * 只传自定义码时前端又无法归类。本方法保留错误码（前端照旧按 A/B/C 处理），
     * 只把 message 换成更具体的说明，例如
     * {@code error(ErrorCode.NO_AUTH_ERROR, "仅管理员可操作")}。
     *
     * <p>为什么不直接改 ErrorCode 里那条枚举的文案：同一个错误码往往被多处复用，
     * 各处的具体原因并不相同（同样是「无权限」，可能是角色不够、也可能是
     * 不是工作室成员）。改枚举等于为了一个场景牺牲其它场景的提示，
     * 而按调用点覆盖才是正确的粒度。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>从错误码取 code，message 用调用方传进来的；</li>
     *     <li>data 显式写 null，与其它失败重载保持同一契约。</li>
     * </ol>
     *
     * <p>容易踩的坑：覆盖 message 时不要把它写成技术性描述（例如「role check failed」），
     * 它会直接展示给最终用户；也别忘了保持与 code 语义一致——
     * 用 A0401 却提示「未登录」会让前端的分支判断与文案互相矛盾。
     *
     * @param errorCode 错误码枚举，只决定 code，不能为 null
     * @param message 覆盖枚举默认提示的文案，必须是能给用户看的完整句子
     * @return 失败响应，code 取自 errorCode、message 取自本参数，data 为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}

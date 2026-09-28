package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;

/**
 * 统一响应封装工具类
 *
 * author: shaoshing
 *
 * <p>为什么需要它：如果每个 Controller 都手写 {@code new BaseResponse<>(0, data, "ok")}，
 * 既啰嗦又容易写错（比如习惯性地把成功码写成 HTTP 的 200）。集中到工具类后，
 * 业务代码一行静态调用即可完成封装，成功码也只有一个来源。
 *
 * <p>使用约定：
 * <ul>
 *     <li>成功一律用 {@link #success(Object)}，成功码固定为 0；</li>
 *     <li>失败必须使用 {@link ErrorCode} 中定义的号段（4xxxx 客户端错误 / 5xxxx 服务端错误），
 *     不要自己临时编数字，否则前端没法归类处理；</li>
 *     <li>失败响应的 data 固定为 null，前端拿到 code != 0 时无需再看 data。</li>
 * </ul>
 */
public class ResultUtils {

    /**
     * 成功响应
     *
     * @param data 业务数据，允许为 null（此时 JSON 里的 data 字段仍然存在，值为 null）
     * @param <T>  业务数据类型，由调用方推断
     * @return 响应对象，code 固定为 0、message 固定为 "ok"
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 失败响应（使用错误码自带的默认提示）
     *
     * @param errorCode 错误码枚举
     * @return 响应对象，data 固定为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 失败响应（自定义状态码）
     * 仅当确实没有对应枚举时才使用，否则优先 {@link #error(ErrorCode)}
     *
     * @param code    业务状态码
     * @param message 提示信息，不得包含异常堆栈等内部细节
     * @return 响应对象，data 固定为 null
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 失败响应（错误码 + 自定义提示）
     * 典型场景：同一个错误码需要给用户更具体的说明，例如 NO_AUTH_ERROR + "仅管理员可操作"
     *
     * @param errorCode 错误码枚举，决定响应中的 code
     * @param message   覆盖枚举默认提示的信息
     * @return 响应对象，data 固定为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}

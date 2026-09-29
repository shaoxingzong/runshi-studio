package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应格式（所有接口的返回值都必须是它）
 *
 * author: shaoshing
 *
 * <p>为什么需要它：前端需要固定结构才能做统一的响应拦截与全局错误提示。
 * 如果有的接口直接返回对象、有的返回字符串、有的返回列表，前端就得给每个接口单独写一套判断逻辑。
 *
 * <p>三个字段的约定：
 * <ul>
 *     <li>{@code code}：业务状态码，0 表示成功，非 0 表示失败（取值见 {@link ErrorCode}）。
 *     它与 HTTP 状态码是解耦的——HTTP 一律返回 200，业务成败只由 code 表达，
 *     这样前端拦截器只需判断一处，不必区分 4xx / 5xx；</li>
 *     <li>{@code data}：业务数据，泛型承载，失败时必须为 null；</li>
 *     <li>{@code message}：给用户看的提示，失败时绝不能包含异常堆栈、SQL 等内部细节。</li>
 * </ul>
 *
 * <p>注意：前端只允许依赖 {@code code} 做逻辑判断，{@code message} 仅用于展示，
 * 不要在前端对 message 做字符串匹配。
 *
 * @param <T> 业务数据的类型
 */
@Data
public class BaseResponse<T> implements Serializable {

    /** 业务状态码：0-成功，非 0-失败 */
    private int code;

    /** 业务数据，用泛型表示（返回前无法确定具体类型）；失败时为 null */
    private T data;

    /** 提示信息，成功时为 "ok" */
    private String message;

    /**
     * 全参构造：业务代码一般不直接调用，优先用 {@link ResultUtils} 的静态方法，
     * 避免各处手写状态码造成不一致
     *
     * @param code    业务状态码
     * @param data    业务数据
     * @param message 提示信息
     */
    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    /**
     * 不传 message 的构造（message 置为空串），供内部复用
     *
     * @param code 业务状态码
     * @param data 业务数据
     */
    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    /**
     * 用错误码枚举构造失败响应：code 与 message 都取自枚举，避免两处手写导致不一致
     *
     * @param errorCode 错误码枚举
     */
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }

}

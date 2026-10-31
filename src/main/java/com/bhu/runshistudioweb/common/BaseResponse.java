package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应格式（所有接口的返回值都必须是它）
 *
 * 前端需要固定结构才能做统一的响应拦截与全局错误提示，否则每个接口都得单独写一套判断逻辑。
 *
 * @author shaoshing
 * @param <T> 业务数据的类型
 */
@Data
public class BaseResponse<T> implements Serializable {

    /** 序列化版本号：实现了 Serializable 就必须固定写死一个值 */
    private static final long serialVersionUID = 1L;

    /** 业务状态码：00000 表示成功，其余为失败（取值见 ErrorCode） */
    private String code;

    /** 业务数据，用泛型表示（返回前无法确定具体类型）；失败时为 null */
    private T data;

    /** 提示信息，成功时为 "ok" */
    private String message;

    /**
     * 全参构造：业务代码一般不直接调用，优先用 ResultUtils 的静态方法，避免各处手写状态码造成不一致
     *
     * @param code 业务状态码
     * @param data 业务数据
     * @param message 提示信息
     */
    public BaseResponse(String code, T data, String message) {
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
    public BaseResponse(String code, T data) {
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

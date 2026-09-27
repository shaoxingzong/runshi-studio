package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一的响应格式
 * @ param <T>
 *
 *  前端开发需要固定的数据结构。如果有的接口直接返回对象，有的返回字符串，有的返回列表，前端就无法做统一的 Response 拦截与全局错误提示。
 */

@Data
public class BaseResponse<T> implements Serializable {

    private int code;

    private T data;  //用泛型来表示数据（因为不知道具体数据类型）

    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}

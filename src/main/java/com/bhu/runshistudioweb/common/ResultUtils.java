package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;

/**
 * 统一响应封装工具类
 *
 * @author shaoshing
 */
public class ResultUtils {

    /**
     * 把业务数据包装成成功响应
     *
     * @param data 业务数据，允许为 null（例如删除接口只关心成败，可以传 true 或 null）；泛型 T 由调用方推断，传错类型会在编译期报错
     * @param <T> 业务数据的类型
     * @return 成功响应，code 固定为 ErrorCode.SUCCESS 的取值、message 固定为它的默认文案
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), data, ErrorCode.SUCCESS.getMessage());
    }

    /**
     * 把错误码包装成失败响应，提示用枚举自带的默认文案
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
     * @param code 业务状态码，必须是 5 位字符串且不与 ErrorCode 已有取值冲突；传 null 不会立刻报错，但前端拿到 null 后无法做分支判断
     * @param message 给用户看的提示文案，不能包含异常堆栈、SQL、内部类名等实现细节
     * @return 失败响应，data 固定为 null
     */
    public static BaseResponse<?> error(String code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 用错误码 + 自定义提示构造失败响应
     *
     * @param errorCode 错误码枚举，只决定 code，不能为 null
     * @param message 覆盖枚举默认提示的文案，必须是能给用户看的完整句子
     * @return 失败响应，code 取自 errorCode、message 取自本参数，data 为 null
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}

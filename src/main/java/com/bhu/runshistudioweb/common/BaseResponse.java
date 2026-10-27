package com.bhu.runshistudioweb.common;

import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应格式（所有接口的返回值都必须是它）
 *
 * 前端需要固定结构才能做统一的响应拦截与全局错误提示，否则每个接口都得单独写一套判断逻辑。
 *
 * 三个字段：code 是业务状态码，00000 表示成功（取值见 ErrorCode），与 HTTP 状态码解耦——
 * HTTP 一律 200，业务成败只由 code 表达；data 泛型承载业务数据，失败时为 null；
 * message 给用户看，失败时绝不能包含异常堆栈、SQL 等内部细节。
 *
 * 前端只允许依赖 code 做逻辑判断，message 仅用于展示，不要对它做字符串匹配。
 *
 * @author shaoshing
 *
 * @param <T> 业务数据的类型
 */
@Data
public class BaseResponse<T> implements Serializable {

    /**
     * 序列化版本号：实现了 Serializable 就必须固定写死一个值
     *
     * 不写的话 JVM 会按类的结构自动算一个 UID，一旦以后给本类加字段，UID 就变了——
     * 再去反序列化「按旧结构写出的」数据会直接抛 InvalidClassException。
     * 这种错误只在读历史数据时才暴露，平时测试发现不了：响应对象被缓存在 Redis
     * 或被另一个服务接收时走的都是 Java 序列化，只要有人这么做就是一颗定时炸弹。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 业务状态码：00000 表示成功，其余为失败（取值见 ErrorCode）
     *
     * 类型是 String 而非 int：错误码是「5 位字符串」= 来源（A/B/C）+ 4 位编号，
     * 首位是字母，数字类型装不下。这也顺带杜绝了前端写 if (code) 把 0 当假值的写法。
     */
    private String code;

    /** 业务数据，用泛型表示（返回前无法确定具体类型）；失败时为 null */
    private T data;

    /** 提示信息，成功时为 "ok" */
    private String message;

    /**
     * 全参构造：业务代码一般不直接调用，优先用 ResultUtils 的静态方法，
     * 避免各处手写状态码造成不一致
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

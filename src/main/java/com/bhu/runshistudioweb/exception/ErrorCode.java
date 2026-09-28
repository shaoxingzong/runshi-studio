package com.bhu.runshistudioweb.exception;

import lombok.Getter;

/**
 * 业务错误码枚举
 *
 * author: shaoshing
 *
 * <p>号段约定（与前端约定好的契约，新增错误码时不要越界）：
 * <ul>
 *     <li>{@code 0}：成功；</li>
 *     <li>{@code 4xxxx}：客户端错误——参数不合法、未登录、无权限、请求过频等，
 *     原样重试没有意义，前端应提示用户；</li>
 *     <li>{@code 5xxxx}：服务端错误——系统异常、操作失败，属于需要排查的问题，
 *     重试可能成功，且对外不能暴露内部细节。</li>
 * </ul>
 *
 * <p>注意：这里的 code 是「业务状态码」，不是 HTTP 状态码，接口的 HTTP 状态一律是 200。
 * 前端拦截器只依赖它做分支判断。
 */
@Getter
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "ok"),

    /** 请求参数错误：参数缺失、格式不合法、超出长度限制等（校验失败也归到这里） */
    PARAMS_ERROR(40000, "请求参数错误"),

    /** 未登录：未携带凭证，或凭证已失效、被篡改 */
    NOT_LOGIN_ERROR(40100, "未登录"),

    /** 无权限：已登录但角色不够，例如普通成员访问管理员接口 */
    NO_AUTH_ERROR(40101, "无权限"),

    /** 请求数据不存在：按 ID 查询无结果、关联数据已被删除 */
    NOT_FOUND_ERROR(40400, "请求数据不存在"),

    /** 禁止访问：账号被封禁、命中黑名单等，属于「明确拒绝」而非「未登录」 */
    FORBIDDEN_ERROR(40300, "禁止访问"),

    /** 请求过于频繁：被限流拦截（见 ratelimiter 包），前端应做退避重试 */
    TOO_MANY_REQUESTS_ERROR(42900, "请求过于频繁"),

    /** 系统内部异常：未预期异常的统一兜底，对外只提示「系统错误」，细节只进日志 */
    SYSTEM_ERROR(50000, "系统内部异常"),

    /** 操作失败：业务上明确的不成功，如更新影响行数为 0、影响行数不符合预期 */
    OPERATION_ERROR(50001, "操作失败");

    /** 状态码 */
    private final int code;

    /** 默认提示信息，可被 {@code ResultUtils.error(errorCode, message)} 覆盖 */
    private final String message;

    /**
     * 枚举构造器（枚举的构造器天然是 private，外部无法 new，保证全局唯一）
     *
     * @param code    状态码
     * @param message 默认提示信息
     */
    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

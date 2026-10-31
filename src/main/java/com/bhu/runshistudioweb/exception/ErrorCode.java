package com.bhu.runshistudioweb.exception;

import lombok.Getter;

/**
 * 业务错误码枚举（遵循《阿里巴巴 Java 开发手册》错误码规范）
 *
 * @author shaoshing
 */
@Getter
public enum ErrorCode {

    // 成功

    /** 成功（手册规定：全部正常、但不得不填充错误码时返回五个零） */
    SUCCESS("00000", "ok"),

    // A 类：错误来源于用户

    /** A0200 用户登录异常：未携带凭证、凭证失效/被篡改、被顶下线 */
    NOT_LOGIN_ERROR("A0201", "未登录"),

    /** A0300 访问权限问题：已登录但角色不足（如普通成员访问管理员接口） */
    NO_AUTH_ERROR("A0301", "无权限"),

    /** A0300 访问权限问题：账号被封禁 */
    FORBIDDEN_ERROR("A0302", "禁止访问"),

    /** A0400 用户请求参数错误：必填项缺失、格式不合法、超出长度或范围 */
    PARAMS_ERROR("A0401", "请求参数错误"),

    /** A0400 用户请求参数错误：按 ID 查询无结果、关联数据已被删除（即请求了不存在的资源） */
    NOT_FOUND_ERROR("A0402", "请求数据不存在"),

    /** A0500 用户请求服务异常：请求次数超出限制（见 manager/AiRateLimitManager） */
    TOO_MANY_REQUESTS_ERROR("A0501", "请求过于频繁"),

    // B 类：错误来源于当前系统

    /** B0001 系统执行出错：未预期异常的统一兜底，对外只提示「系统错误」，细节只进日志 */
    SYSTEM_ERROR("B0001", "系统内部异常"),

    /** B0002 业务操作失败：更新影响行数不为 1、切分结果为空等「预期内但不成功」 */
    OPERATION_ERROR("B0002", "操作失败"),

    // C 类：错误来源于第三方服务

    /** C0200 第三方系统执行超时 / 不可用：AI 对话与向量化服务（阿里云百炼等 OpenAI 兼容服务） */
    AI_SERVICE_ERROR("C0200", "AI 服务暂时不可用");

    /** 状态码：5 位字符串 = 来源（A/B/C）+ 4 位编号 */
    private final String code;

    /** 默认提示信息，可被 ResultUtils.error(errorCode, message) 覆盖 */
    private final String message;

    /**
     * 枚举构造器（枚举的构造器天然是 private，外部无法 new，保证全局唯一）
     *
     * @param code 状态码（5 位：来源 + 4 位编号）
     * @param message 默认提示信息
     */
    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}

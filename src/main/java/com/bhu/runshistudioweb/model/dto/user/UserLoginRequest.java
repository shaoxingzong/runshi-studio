package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求
 *
 * author: shaoshing
 *
 * <p>登录请求只允许传账号与密码两个字段：
 * 用 DTO 而不是实体，可以杜绝前端夹带 {@code userRole}、{@code userStatus} 之类的字段
 * （即使 Service 不读它们，也是潜在的风险点，且会让日志里出现不该有的信息）。
 *
 * <p>这里**不校验长度**：登录只做「能不能对上」的判断。
 * 如果在这里限制 8~20 位，历史密码规则变更后的老用户会直接被拦下，
 * 拿到的提示还是「参数错误」而不是「密码错误」，排查成本更高。
 */
@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户账号
     */
    @NotBlank(message = "账号不能为空")
    private String userAccount;

    /**
     * 用户密码（明文，仅在 HTTPS 链路上传输，服务端不落库、不进日志）
     */
    @NotBlank(message = "密码不能为空")
    private String userPassword;
}

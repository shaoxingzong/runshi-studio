package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求
 *
 * 用 DTO 而不是实体：杜绝前端夹带 userRole、userStatus 之类的字段。
 *
 * 这里不校验长度：登录只做「能不能对上」的判断。限制 8~20 位会把历史密码规则下的老用户
 * 直接拦下，且提示是「参数错误」而非「密码错误」，排查成本更高。
 *
 * @author shaoshing
 */
@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户账号 */
    @NotBlank(message = "账号不能为空")
    private String userAccount;

    /** 用户密码（明文，仅在 HTTPS 链路上传输，服务端不落库、不进日志） */
    @NotBlank(message = "密码不能为空")
    private String userPassword;
}

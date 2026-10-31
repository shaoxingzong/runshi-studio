package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求
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

package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户自助修改密码请求
 *
 * 与注册同一套长度规则（8~20 位）：密码规则一旦放宽或收紧，
 * 已注册用户的存量密码就会与新规则对不上，所以这里只做与注册一致的长度兜底。
 *
 * @author shaoshing
 */
@Data
public class UserChangePasswordRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原密码：用于证明「是本人操作」，而不是捡到别人未退出的页面就能改 */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /** 新密码：8~20 位，入库前用 BCrypt 加密 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在 8 ~ 20 位之间")
    private String newPassword;

    /** 确认密码：必须与 newPassword 一致（字段间比较只能由 Service 校验） */
    @NotBlank(message = "确认密码不能为空")
    private String checkPassword;
}

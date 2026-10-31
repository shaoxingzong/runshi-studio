package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：新增用户请求
 *
 * @author shaoshing
 */
@Data
public class UserAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户账号（必填，唯一） */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 16, message = "账号长度需为 4-16 位")
    private String userAccount;

    /** 用户密码（选填）为空时由 Service 使用默认初始密码；管理员创建的账号应要求首次登录后修改密码 */
    @Size(min = 8, max = 20, message = "密码长度需为 8-20 位")
    private String userPassword;

    /** 用户昵称（选填）为空时用账号兜底：DDL 中 user_name 是 NOT NULL 且无默认值，不赋值会插入失败 */
    @Size(max = 64, message = "昵称长度不能超过 64 个字符")
    private String userName;

    /** 用户头像 URL（选填）一般先调 /file/upload 拿到相对路径，再填到这里 */
    @Size(max = 512, message = "头像地址过长")
    private String userAvatar;

    /** 用户角色（选填），取值见 com.bhu.runshistudioweb.model.enums.UserRoleEnum 不填按普通用户处理。 */
    private String userRole;

    /** 用户状态（选填）：0-正常，1-封禁；不填按 0 处理 */
    private Integer userStatus;
}

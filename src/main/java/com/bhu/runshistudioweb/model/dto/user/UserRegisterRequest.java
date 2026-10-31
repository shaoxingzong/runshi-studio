package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册请求
 *
 * @author shaoshing
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户账号：4~16 位，只允许字母、数字、下划线。加 @Pattern 是防止账号里出现空格、中文、特殊符号——它们会让登录时的输入匹配变得不可预期 */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 16, message = "账号长度需在 4 ~ 16 位之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "账号只能包含字母、数字和下划线")
    private String userAccount;

    /** 真实姓名：站内展示用（右上角、AI 会话等）；登录仍使用账号，长度上限与 DDL 的 user_name varchar(64) 对齐 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64 个字符")
    private String userName;

    /** 用户密码：8~20 位，由 Service 用 BCrypt 加密后入库。不加「必须含特殊字符」这类规则：后端只做长度兜底，否则老密码规则一变，已注册用户全部无法登录 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在 8 ~ 20 位之间")
    private String userPassword;

    /** 确认密码：必须与 userPassword 一致（一致性由 Service 校验，注解无法做字段间比较）前端校验只是体验优化，请求可以绕过页面直接构造，后端必须再校验一次 */
    @NotBlank(message = "确认密码不能为空")
    private String checkPassword;

}

package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册请求
 *
 * author: shaoshing
 *
 * <p>为什么用独立 DTO 接收参数，而不是直接用 {@code SysUser} 实体：
 * <ul>
 *     <li>实体里的字段（id、userRole、userStatus、createdAt…）绝不能让前端传，
 *     否则可以构造请求把自己注册成 admin，或直接伪造创建时间——这是最典型的参数越权；</li>
 *     <li>DTO 只暴露注册必需的三个字段，天然做到「最小暴露面」。</li>
 * </ul>
 *
 * <p>校验注解由 Controller 上的 {@code @Valid} 触发，失败会抛 {@code MethodArgumentNotValidException}，
 * 由全局异常处理器统一转成 A0401 与第一条错误提示。
 *
 * <p>注意：这里的规则必须与 {@code UserServiceImpl.userRegister} 里的手写校验保持一致，
 * 否则会出现「DTO 说 16 位、Service 说 20 位」两套标准（目前就不一致，建议统一为一处）。
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户账号：4~16 位，只允许字母、数字、下划线
     * <p>加 {@code @Pattern} 是为了防止账号里出现空格、中文、特殊符号——
     * 它们会让登录时的输入匹配变得不可预期（例如用户看不出首尾空格）
     */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 16, message = "账号长度需在 4 ~ 16 位之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "账号只能包含字母、数字和下划线")
    private String userAccount;

    /**
     * 用户密码：8~20 位，由 Service 用 BCrypt 加密后入库
     * <p>注意不要在这里加「必须含特殊字符」这类规则：密码强度提示应放在前端，
     * 后端只做长度与必要的兜底，否则老密码规则一变，已注册用户全部无法登录
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度需在 8 ~ 20 位之间")
    private String userPassword;

    /**
     * 确认密码：必须与 userPassword 一致（一致性由 Service 校验，注解无法做字段间比较）
     * <p>前端校验只是「体验优化」，后端必须再校验一次，因为请求可以绕过页面直接构造
     */
    @NotBlank(message = "确认密码不能为空")
    private String checkPassword;

}

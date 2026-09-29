package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：更新用户请求
 *
 * author: shaoshing
 *
 * <p><b>语义是「部分更新」</b>：只有非 null 的字段才会被更新，传 null 表示「这一项不动」。
 * 这依赖 MyBatis-Plus 的默认策略（null 字段不参与 UPDATE），因此：
 * <ul>
 *     <li>前端做「只改昵称」的表单时，不必先把整行数据查出来回传，避免了自己漏字段导致误清空的坑；</li>
 *     <li>但代价是**无法通过本接口把某个字段清空**（例如把头像置空），
 *     真有这种需求要单独设计一个「重置头像」的接口，而不是用传空串来模糊表达。</li>
 * </ul>
 *
 * <p>安全约束（都在 Service 侧执行）：
 * <ul>
 *     <li>{@code userRole} 必须是枚举中的合法取值，防止写进任意字符串导致权限判定失效；</li>
 *     <li>不允许管理员把自己封禁（否则会立刻失去管理端权限，需要去数据库改数据才能恢复）。</li>
 * </ul>
 */
@Data
public class UserUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（必填）
     * <p>校验「必须为正数」是廉价的兜底：能挡住 -1、0 这类明显非法的探测请求，
     * 避免它们打到数据库再返回「不存在」这种误导性提示
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;

    /**
     * 用户昵称（选填，null 表示不修改）
     */
    @Size(max = 64, message = "昵称长度不能超过 64 个字符")
    private String userName;

    /**
     * 用户头像 URL（选填，null 表示不修改）
     */
    @Size(max = 512, message = "头像地址过长")
    private String userAvatar;

    /**
     * 用户角色（选填，null 表示不修改），取值见
     * {@link com.bhu.runshistudioweb.model.enums.UserRoleEnum}
     */
    private String userRole;

    /**
     * 用户状态（选填，null 表示不修改）：0-正常，1-封禁
     * <p>封禁只影响后续登录与权限判定，不会立刻吊销已下发的 token
     * （权限判定每次都会查库，因此被封禁的账号即使 token 未过期也会被判定为无角色）
     */
    private Integer userStatus;

    /**
     * 重置密码（选填）
     * <p>传值即重置（内部 BCrypt 加密后覆盖），传 null 表示不改密码。
     * 管理员重置密码后应通过安全渠道告知用户，并建议用户尽快自行修改
     */
    @Size(min = 8, max = 20, message = "密码长度需为 8-20 位")
    private String userPassword;
}

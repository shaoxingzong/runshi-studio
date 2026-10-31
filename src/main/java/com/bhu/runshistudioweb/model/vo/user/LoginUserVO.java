package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录用户视图对象（VO）
 *
 * @author shaoshing
 */
@Data
public class LoginUserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（雪花算法，19 位） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户账号 */
    private String userAccount;

    /** 用户昵称 */
    private String userName;

    /** 用户头像 */
    private String userAvatar;

    /** 用户角色（user / member / admin）与 @SaCheckRole 的比较值完全一致，取值见 UserRoleEnum； */
    private String userRole;

    /** 用户状态（0-正常，1-封禁）虽然封禁用户无法登录，但前端拿到状态位可以给出更准确的提示 */
    private Integer userStatus;

    /** AI 查询次数 */
    private Integer aiQueryCount;

    /** 登录凭证 Token：只在登录接口返回，后续请求由前端放在请求头 satoken 上带回 获取当前用户信息等接口不会再返回 token（前端已有，重复下发只会增加泄露面） */
    private String token;

    /** 创建时间。由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss，前端无需再处理 ISO 的 T */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

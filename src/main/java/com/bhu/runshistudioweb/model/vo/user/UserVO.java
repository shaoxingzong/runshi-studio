package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（管理端列表 / 详情使用）
 *
 * @author shaoshing
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（雪花算法 19 位）与 LoginUserVO 一致，显式标注 ToStringSerializer 转成字符串，避免前端因超出 JS 安全整数范围而丢精度 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户账号 */
    private String userAccount;

    /** 用户昵称 */
    private String userName;

    /** 用户头像 URL */
    private String userAvatar;

    /** 用户角色，取值见 com.bhu.runshistudioweb.model.enums.UserRoleEnum */
    private String userRole;

    /** 用户状态：0-正常，1-封禁。管理端必须能看到它，否则「为什么这个账号登不上去」无从判断 */
    private Integer userStatus;

    /** AI 提问已用次数（数据库基准值） */
    private Integer aiQueryCount;

    /** 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss） */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

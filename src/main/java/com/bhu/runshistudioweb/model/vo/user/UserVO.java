package com.bhu.runshistudioweb.model.vo.user;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（管理端列表 / 详情使用）
 *
 * 与 LoginUserVO 的分工：
 * - LoginUserVO：给「用户自己」看，含 token，不含状态位；
 * - UserVO：给「管理员」看，含角色与状态，但永远不含密码——两个 VO 都不含，
 * 这是刻意的：不把敏感字段暴露到即使拷贝也不会带上的实体结构里。
 *
 * 修掉的一个隐藏 bug：原实现的 createTime / updateTime 用的是
 * java.util.Date，而实体里是 LocalDateTime。BeanUtils.copyProperties
 * 遇到类型不一致会静默跳过（不报错、不提示），结果是管理端列表里时间列永远是空，
 * 并且必须联调时才会发现。现在统一为 LocalDateTime，与实体一致，拷贝才能生效。
 *
 * 字段命名与设计约定（时间字段统一 _at 后缀）：
 * 数据库列是 created_at / updated_at，Java 侧统一叫 createdAt / updatedAt，
 * 不要再用 createTime / updateTime 这类别名，否则一个概念两个名字，联调时极易对错。
 *
 * @author shaoshing
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID（雪花算法 19 位）
     * 与 LoginUserVO 保持一致，显式标注 Jackson 3 的序列化器转成字符串，
     * 避免前端因超出 JS 安全整数范围而丢失精度
     */
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

    /**
     * 用户状态：0-正常，1-封禁
     * 管理端必须能看到它，否则「为什么这个账号登不上去」无从判断
     */
    private Integer userStatus;

    /** 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss） */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

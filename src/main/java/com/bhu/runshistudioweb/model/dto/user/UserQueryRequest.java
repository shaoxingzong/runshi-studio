package com.bhu.runshistudioweb.model.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：分页查询用户请求
 *
 * 不继承 Spring Data 的 PageRequest：它没有无参构造、字段是私有 final，Spring MVC 绑不上参数，
 * 而且它属于 JPA 体系，与 MyBatis-Plus 的 Page 是两套分页模型。本类只是普通参数载体，
 * 分页字段自己声明，由 Service 转成 Page。
 *
 * id、userRole、userStatus 精确匹配，userAccount、userName 模糊匹配；字段为 null 表示该条件不参与筛选。
 * 分页与排序的非法值不在这里报错（GET 传参校验失败会变成 B0001），由 Service 兜底纠正。
 *
 * @author shaoshing
 */
@Data
public class UserQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 id（精确匹配） */
    private Long id;

    /** 用户账号（模糊查询；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String userAccount;

    /** 用户昵称（模糊查询；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String userName;

    /** 用户角色（精确匹配），取值见 com.bhu.runshistudioweb.model.enums.UserRoleEnum */
    private String userRole;

    /** 用户状态（精确匹配）：0-正常，1-封禁 */
    private Integer userStatus;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛到上限（防止一次拉全表） */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / userAccount / userName / userStatus / createdAt
     * 只允许这几个值，Service 里用白名单映射成实体字段，绝不能把前端字符串直接拼进 SQL
     */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理 */
    private String sortOrder;
}

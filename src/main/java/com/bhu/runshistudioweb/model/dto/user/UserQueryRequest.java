package com.bhu.runshistudioweb.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：分页查询用户请求
 *
 * author: shaoshing
 *
 * <p><b>这里修掉了一个隐患</b>：原实现写成 {@code extends PageRequest}
 * （{@code org.springframework.data.domain.PageRequest}），有两个致命问题：
 * <ol>
 *     <li>Spring Data 的 PageRequest 是**反序列化不出来**的：它没有无参构造、字段是私有的 final，
 *     Spring MVC 无法把 {@code ?current=1} 绑定进去，接口一调就报错；</li>
 *     <li>它属于 Spring Data JPA 体系，和 MyBatis-Plus 的 {@code Page} 是两套分页模型，
 *     混用等于把「页码从 1 开始还是从 0 开始」「每页条数叫什么名字」这些细节交给两套规则去打架。</li>
 * </ol>
 * 现在的定位很单纯：**一个普通的查询参数载体**，分页字段由本类自己声明，
 * 由 Service 转成 MyBatis-Plus 的 {@code Page}。
 *
 * <p>字段语义：
 * <ul>
 *     <li>{@code id}、{@code userRole}、{@code userStatus} 为精确匹配；</li>
 *     <li>{@code userAccount}、{@code userName} 为模糊匹配；</li>
 *     <li>字段为 null 表示「该条件不参与筛选」，不是「查 null 值」。</li>
 * </ul>
 *
 * <p>注意：分页与排序参数的非法值**不在这里报错**，而是在 Service 里做兜底纠正
 * （例如 {@code current < 1} 视为 1、{@code pageSize} 上限 50）。
 * 原因有两个：一是这些参数用 GET 传递，校验失败抛的是 BindException，
 * 会落到全局异常处理器的兜底分支变成 B0001，体验很差；
 * 二是「页码传错」没必要让整个查询失败，纠正成合理值继续查更符合预期。
 */
@Data
public class UserQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 id（精确匹配）
     */
    private Long id;

    /**
     * 用户账号（模糊查询）
     */
    private String userAccount;

    /**
     * 用户昵称（模糊查询）
     */
    private String userName;

    /**
     * 用户角色（精确匹配），取值见 {@link com.bhu.runshistudioweb.model.enums.UserRoleEnum}
     */
    private String userRole;

    /**
     * 用户状态（精确匹配）：0-正常，1-封禁
     */
    private Integer userStatus;

    /**
     * 页码，从 1 开始；不传按 1 处理
     */
    private Long current = 1L;

    /**
     * 每页条数；不传按 10 处理，超过上限会被收敛到上限（防止一次拉全表）
     */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / userAccount / userName / userStatus / createdAt
     * <p>只允许这几个值，Service 里用白名单映射成实体字段。
     * 绝不能把前端传来的字符串直接拼进 SQL——那是最典型的 SQL 注入入口
     */
    private String sortField;

    /**
     * 排序方向：asc / desc，不传或非法按 desc 处理
     */
    private String sortOrder;
}

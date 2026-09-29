package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表实体（对应 sys_user）
 *
 * author: shaoshing
 *
 * <p>字段与列的映射依赖 MyBatis-Plus 的「驼峰 ↔ 下划线」自动转换：
 * {@code userAccount → user_account}，因此不需要给每个字段写 {@code @TableField("...")}。
 * 一旦某个字段的列名不符合这个规律，必须显式声明，否则会报「Unknown column」。
 *
 * <p>三条全局约定（详见 db/DESIGN.md）：
 * <ul>
 *     <li><b>主键策略</b>：统一用雪花算法 {@code ASSIGN_ID}，DDL 里已移除 AUTO_INCREMENT。
 *     两套策略绝不能并存，否则 MP 写入的雪花 ID 会把自增计数器顶到天文数字；</li>
 *     <li><b>逻辑删除</b>：{@code deleted_at} 为毫秒时间戳，0 表示未删除。
 *     所有唯一索引都必须带上该列，否则注销后的账号会永久占用账号名、无法重新注册（ADR-1）；</li>
 *     <li><b>时间字段</b>：统一 {@code _at} 后缀、{@code datetime} 类型（不用 timestamp，避免 2038 与时区隐式转换），
 *     值由 {@link com.bhu.runshistudioweb.config.MyMetaObjectHandler} 自动填充。</li>
 * </ul>
 *
 * <p>注意：本类字段与 DDL 一一对应，不要在此直接加业务展示字段；
 * 对外返回时应使用 VO，避免密码等敏感字段泄露。
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     * 因为超出 JS 安全整数范围，接口返回时会由 JsonConfig 序列化为字符串
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 登录账号：唯一，且必须参与 (account, deleted_at) 唯一索引
     */
    private String userAccount;

    /**
     * 密码：存 BCrypt 加密串，绝不存明文，也绝不返回给前端
     */
    private String userPassword;

    /**
     * 真实姓名/昵称
     */
    private String userName;

    /**
     * 头像 URL
     */
    private String userAvatar;

    /**
     * 权限角色：user-普通注册用户, member-工作室成员, admin-管理员
     * 取值定义见 {@link com.bhu.runshistudioweb.model.enums.UserRoleEnum}，
     * 必须与 DDL 注释、{@code @SaCheckRole} 入参保持一致（三处任一不一致都会静默判定为无权限）
     * 用 varchar 而非 tinyint，是为了排障时一眼读懂、且新增角色不必改表（DESIGN.md 4.2）
     */
    private String userRole;

    /**
     * 手机号（未填写时必须存 NULL，禁止存空字符串）
     * 原因：唯一索引不约束 NULL，若写 ''，第二个未填手机号的账号会插入失败
     */
    private String phone;

    /**
     * 邮箱（未填写时必须存 NULL，禁止存空字符串），约束同手机号
     */
    private String email;

    /**
     * 用户状态：0-正常，1-封禁（枚举值域由 db/user.sql 的列注释维护）
     * 与 userRole 是两个正交维度，不要合并成一个「带 ban 的角色」字段（ADR-4）
     */
    private Integer userStatus;

    /**
     * AI 查询次数：热点行，每次提问都会 UPDATE 同一行，高并发下存在行锁竞争（风险 R1，）
     */
    private Integer aiQueryCount;

    /**
     * 创建人 ID：插入时由 MyMetaObjectHandler 填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 更新人 ID：插入与更新时都会填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 创建时间：插入时填充。字段名用 _at 后缀、类型用 LocalDateTime（映射 datetime）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间：插入与更新时都会填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     *
     * <p>加 {@code @TableLogic} 后，MP 会自动改写 SQL：
     * 查询追加 {@code deleted_at = 0}，删除则改成 UPDATE（值取自 yml 中的
     * {@code CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)}，每次删除取值都不同）。
     *
     * <p>注意：本字段**不能**加 {@code @TableField(fill = ...)}，它由逻辑删除机制自己维护；
     * 同时关联表实体不能出现本字段，否则逻辑删除会对中间表生效，与物理删除的设计冲突。
     */
    @TableLogic
    private Long deletedAt;
}

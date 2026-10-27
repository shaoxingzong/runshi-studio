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
 * 三条全局约定：雪花主键 ASSIGN_ID（DDL 不带自增，两套策略并存会把自增计数器顶到天文数字）；
 * 逻辑删除 deleted_at 为毫秒时间戳（所有唯一索引都要带上它，否则注销的账号永久占用账号名）；
 * 时间列统一 _at 后缀 + datetime（不用 timestamp，避开 2038 与时区隐式转换）。
 *
 * 坑：字段与 DDL 一一对应，不要在此加业务展示字段；对外一律走 VO，避免密码泄露。
 *
 * @author shaoshing
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法（19 位 Long），不走自增；超出 JS 安全整数，返回时序列化为字符串 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录账号：唯一，且必须参与 (account, deleted_at) 唯一索引 */
    private String userAccount;

    /** 密码：存 BCrypt 加密串，绝不存明文，也绝不返回给前端 */
    private String userPassword;

    /** 真实姓名/昵称 */
    private String userName;

    /** 头像 URL */
    private String userAvatar;

    /** 权限角色：user / member / admin，见 UserRoleEnum；须与 DDL 注释、@SaCheckRole 入参三处一致 */
    private String userRole;

    /** 手机号：未填写必须存 NULL，存 '' 会让第二个未填手机号的账号插入失败（唯一索引不约束 NULL） */
    private String phone;

    /** 邮箱（未填写时必须存 NULL，禁止存空字符串），约束同手机号 */
    private String email;

    /** 用户状态：0-正常，1-封禁；与 userRole 正交，不要合并成「带 ban 的角色」 */
    private Integer userStatus;

    /**
     * AI 查询次数基准值，不是实时用量：增量先进 Redis，由定时任务批量回刷
     * 读时必须取「本字段 + Redis 增量」的合并值，只查这里会读到回刷前的旧值
     */
    private Integer aiQueryCount;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间：插入时由 MyMetaObjectHandler 填充，映射 datetime */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     * @TableLogic 让查询追加 deleted_at = 0，删除改写为 UPDATE；不能加 @TableField(fill = ...)
     */
    @TableLogic
    private Long deletedAt;
}

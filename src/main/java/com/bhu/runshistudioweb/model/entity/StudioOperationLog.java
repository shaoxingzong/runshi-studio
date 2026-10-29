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
 * 管理端操作日志实体（对应 studio_operation_log）
 *
 * 一行 = 一次「会改变内容或权限」的管理员动作。留痕的价值全在事故之后：
 * 内容出了问题，能直接回答「谁、什么时候、对哪条、做了什么」，而不是先复原现场再猜。
 *
 * operator_account / operator_name / target_title 存的是**快照**而非外键：
 * 账号被删、帖子被删之后，日志仍然是可读证据；只存 ID 等于把可读性押在「数据还在」上。
 *
 * 本表只增不改：没有业务更新路径，updated_at 实际恒等于 created_at。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_operation_log")
public class StudioOperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long，接口层序列化为字符串） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人 ID（sys_user.id；0 表示系统或未登录，正常管理操作不应为 0） */
    private Long operatorId;

    /** 操作人账号快照（账号删除后仍然可读） */
    private String operatorAccount;

    /** 操作人昵称快照（可能为空——账号没填昵称） */
    private String operatorName;

    /** 动作码，取值见 OperationTypeEnum（字符串而非数字：排障一眼读懂、新增动作不改表） */
    private String action;

    /** 对象类型：POST / COMMENT / USER / QUIZ，与动作码同源（见 OperationTypeEnum） */
    private String targetType;

    /** 对象 ID；章节 / 教材这类没有独立 ID 的动作允许为 null */
    private Long targetId;

    /** 对象标题快照（帖子标题 / 题目题干摘要 / 用户名等），超长由 Service 截断 */
    private String targetTitle;

    /** 处置细节（驳回理由、角色旧值→新值、删除了多少条）；**禁止写入口令、token 与正文** */
    private String detail;

    /** 来源 IP（IPv6 最长 45 字符）；解析不到时为 null，不因此让业务失败 */
    private String ip;

    /** 发生时间（insert 时由 MyMetaObjectHandler 填充；DDL 无 DB 默认值，漏注解会插入失败） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（本表无更新路径，恒等于 createdAt，保留字段只为与其它表结构统一） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 创建人 ID（insert 时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 修改人 ID（insert / update 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 逻辑删除毫秒时间戳（0-未删除）；由逻辑删除机制维护，不能加 @TableField(fill = ...) */
    @TableLogic
    private Long deletedAt;
}

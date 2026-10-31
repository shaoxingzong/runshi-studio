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
 * 帖子评论实体（对应 studio_post_comment，楼中楼）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_post_comment")
public class StudioPostComment implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属帖子 ID（关联 studio_post.id） */
    private Long postId;

    /** 评论人 ID（sys_user.id）：登录用户即可评论，不要求是工作室成员（这是与发帖的区别） */
    private Long authorId;

    /** 父评论 ID（楼中楼）；null 表示直接评论帖子 */
    private Long parentId;

    /** 评论正文（纯文本，最长 1000 字符，与列宽一致） */
    private String content;

    /** 楼层号（顶层从 1 递增）；回复为 null，给回复编号会让「第几楼」的语义混乱 */
    private Integer floor;

    /** 审核状态：0-待审, 1-已通过, 2-已驳回 */
    private Integer status;

    /** 驳回理由（给用户看；仅「已驳回」时有意义） */
    private String rejectReason;

    /** 审核人 ID（管理员）；AI 自动处置时留空，机器结论记在 aiAuditStatus */
    private Long auditBy;

    /** 审核时间 */
    private LocalDateTime auditAt;

    /** AI 审核结论：0-未判，1-安全，2-违规，3-灰色，4-失败，见 AiAuditStatusEnum；异步任务以「未判」作幂等条件 */
    private Integer aiAuditStatus;

    /** AI 给出的理由（给管理端参考；给用户看的驳回说明另存 #rejectReason） */
    private String aiReason;

    /** AI 判定时（未判则为 null） */
    private LocalDateTime aiAt;

    /** 删除人 ID（管理员删除评论时的审计字段） */
    private Long deletedBy;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；删顶层评论时其回复一并删除，不留悬空回复 */
    @TableLogic
    private Long deletedAt;
}

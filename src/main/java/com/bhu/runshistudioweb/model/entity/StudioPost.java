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
 * 帖子实体（对应 studio_post）
 *
 * @author shaoshing
 */
@Data
@TableName("studio_post")
public class StudioPost implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 帖子标题 */
    private String title;

    /** 摘要（列表展示用；为空时由 Service 用正文前若干字兜底） */
    private String summary;

    /** 封面图 URL：/uploads/... 相对路径，入库时自动取正文首图，可人工覆盖 */
    private String coverImage;

    /** 正文（Markdown，图片内嵌其中）：text 列，长度上限由 Service 校验 */
    private String content;

    /** 发帖人 ID（sys_user.id）：只有绑定的在队成员会发帖，成员资格由 studio_member 判定 */
    private Long authorId;

    /** 审核状态：0-待审，1-已通过，2-已驳回，取值见 AuditStatusEnum（与评论共用） */
    private Integer status;

    /** 驳回理由（给用户看；仅「已驳回」时有意义） */
    private String rejectReason;

    /** 审核人 ID（管理员）；AI 自动处置时留空，机器结论记在 aiAuditStatus */
    private Long auditBy;

    /** 审核时间 */
    private LocalDateTime auditAt;

    /** AI 审核结论：0-未判，1-安全，2-违规，3-灰色，4-失败，见 AiAuditStatusEnum；异步任务以「未判」作幂等条件 */
    private Integer aiAuditStatus;

    /** AI 判断依据（原样保留给管理端）；给用户看的说明是 rejectReason */
    private String aiReason;

    /** AI 判定时（未判则为 null） */
    private LocalDateTime aiAt;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数（冗余列，由评论的审核通过 / 删除维护） */
    private Integer commentCount;

    /** 点赞数（冗余列）：只能赞不能取消，只增不减；自增必须是 SQL 级 SET like_count = like_count + 1 */
    private Integer likeCount;

    /** 是否置顶：0-否，1-是；用 Integer 而非 Boolean，DDL 是 tinyint 且可能扩展语义 */
    private Integer pinned;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除，非0-已删除）；@TableLogic 让查询追加 deleted_at = 0，删除改写为 UPDATE */
    @TableLogic
    private Long deletedAt;
}

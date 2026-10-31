package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论视图对象（管理端待审队列专用）
 *
 * @author shaoshing
 */
@Data
public class PostCommentAuditVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评论 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属帖子 ID：管理员要知道这条评论挂在哪个帖子下 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long postId;

    /** 评论人 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 评论人名（由 Service 批量查出后填充） */
    private String authorName;

    /** 父评论 ID：null 表示直接评论帖子；非 null 表示是某条评论的回复 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 评论正文 */
    private String content;

    /** 楼层号（回复某条评论时为 null） */
    private Integer floor;

    /** 审核状态：0-待审, 1-已通过, 2-已驳回 */
    private Integer status;

    /** 驳回理由（仅已驳回时有意义） */
    private String rejectReason;

    /** 审核时间 */
    private LocalDateTime auditAt;

    /** AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工) */
    private Integer aiAuditStatus;

    /** AI 给出的理由：管理员据此快速判断机器在犹豫什么 */
    private String aiReason;

    /** AI 判定时（未判为 null） */
    private LocalDateTime aiAt;

    /** 评论时间 */
    private LocalDateTime createdAt;
}

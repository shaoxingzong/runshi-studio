package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论视图对象（<b>管理端待审队列专用</b>）
 *
 * author: shaoshing
 *
 * <p><b>为什么不复用 {@link PostCommentVO}</b>：那个 VO 用于「某帖子的评论列表」，
 * 是<b>游客可访问</b>的公开接口，因此刻意不含 status、rejectReason、审计字段。
 * 而管理端的待审队列恰恰需要这些——尤其现在要展示 AI 的初判结论与理由。
 * 给 {@link PostCommentVO} 加上这些字段，等于把内部审核信息暴露给游客；
 * 本项目的脱敏纪律正是「靠目标 VO 没有这些字段」，所以只能分开建。
 *
 * <p><b>AI 三列是这个类存在的主要理由</b>：管理员打开队列时，
 * 内容已被 AI 分好类（安全 / 违规 / 灰色 / 判不了），
 * 他只需要处理「灰色」与「判不了」两类，安全与违规的早已被自动处置、不在队列里。
 * {@code aiReason} 给出机器的疑虑，管理员据此一眼就能拍板。
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

    /**
     * AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum}。
     * 队列里的条目绝大多数是 3 或 4——1 与 2 已被自动处置，不会出现在待审队列中。
     */
    private Integer aiAuditStatus;

    /** AI 给出的理由：管理员据此快速判断机器在犹豫什么 */
    private String aiReason;

    /** AI 判定时（未判为 null） */
    private LocalDateTime aiAt;

    /** 评论时间 */
    private LocalDateTime createdAt;
}

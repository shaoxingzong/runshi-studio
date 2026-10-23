package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（作者看自己的，含待审与驳回）
 *
 * 与 PostFrontVO 的分工：
 * - PostFrontVO：给所有人看的公开列表，只有已通过的内容；
 * - 本类：给作者本人看的「我的发帖」，三种状态都要能看到——
 * 尤其是已驳回要带上驳回理由，否则用户不知道为什么发不出去、也不知道该怎么改。
 *
 * 为什么不含 content：作者看列表是为了管理自己的发帖（看状态、看数据），
 * 不是重新读一遍正文；正文走详情接口即可。这样即使一个人发了几百篇，列表也不会膨胀。
 *
 * 先审后发的体验关键就在本类：待审内容只有作者自己能看到，
 * 所以「发出去了但没显示」这件事必须有个明确的去处——就是这里的 status。
 *
 * authorId / authorName 只服务管理端：管理员的帖子列表要能
 * 定位到「这是谁发的」，否则筛选与核实身份都无从下手。对外的 PostFrontVO
 * 只暴露 authorName，不给 ID——对外没必要知道作者的账号主键。
 */
@Data
public class PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子 ID（序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 作者 ID（关联 sys_user.id，管理端用来定位到人；序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /**
     * 作者昵称（管理端列表展示）
     *
     * 由 Service 批量查 sys_user 填充（一次查询，不逐条查）。
     * 取 user_name，为空时兜底 user_account——
     * 有些账号只设了账号名，显示总比留空好。
     */
    private String authorName;

    /** 帖子标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面图 URL */
    private String coverImage;

    /**
     * 审核状态：0-待审, 1-已通过, 2-已驳回
     *
     * 取值定义见 com.bhu.runshistudioweb.model.enums.AuditStatusEnum。
     */
    private Integer status;

    /** 驳回理由（仅"已驳回"时有意义，给用户看） */
    private String rejectReason;

    /** 审核时间（待审时为 null） */
    private LocalDateTime auditAt;

    /**
     * AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
     *
     * 取值定义见 com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum。
     *
     * 本类同时服务于「作者看自己的发帖」与管理端的待审队列，
     * 因此作者也能看到 AI 的初判结论——这是刻意的透明度：
     * 对作者来说，「AI 认为需要人工复核」远比只显示「待审」更有信息量，
     * 他据此知道内容在正常流转、而不是被静默丢弃。
     * 且这些结论涉及的都是他自己的内容，不构成他人信息泄露。
     */
    private Integer aiAuditStatus;

    /** AI 给出的理由（管理端据此快速处置；作者看到的是针对自己内容的说明） */
    private String aiReason;

    /** AI 判定时（未判为 null） */
    private LocalDateTime aiAt;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数 */
    private Integer commentCount;

    /** 点赞数（冗余列；管理端审核时可据此判断内容热度） */
    private Integer likeCount;

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

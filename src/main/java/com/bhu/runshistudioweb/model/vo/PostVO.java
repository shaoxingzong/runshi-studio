package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（<b>作者看自己的</b>，含待审与驳回）
 *
 * author: shaoshing
 *
 * <p>与 {@link PostFrontVO} 的分工：
 * <ul>
 *     <li>{@link PostFrontVO}：给所有人看的<b>公开列表</b>，只有已通过的内容；</li>
 *     <li>本类：给<b>作者本人</b>看的「我的发帖」，三种状态都要能看到——
 *     尤其是<b>已驳回要带上驳回理由</b>，否则用户不知道为什么发不出去、也不知道该怎么改。</li>
 * </ul>
 *
 * <p><b>为什么不含 {@code content}</b>：作者看列表是为了管理自己的发帖（看状态、看数据），
 * 不是重新读一遍正文；正文走详情接口即可。这样即使一个人发了几百篇，列表也不会膨胀。
 *
 * <p>先审后发的体验关键就在本类：待审内容只有作者自己能看到，
 * 所以「发出去了但没显示」这件事必须有个明确的去处——就是这里的 {@code status}。
 */
@Data
public class PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子 ID（序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 帖子标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面图 URL */
    private String coverImage;

    /**
     * 审核状态：0-待审, 1-已通过, 2-已驳回
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AuditStatusEnum}。
     */
    private Integer status;

    /**
     * 驳回理由（仅"已驳回"时有意义，给用户看）
     */
    private String rejectReason;

    /** 审核时间（待审时为 null） */
    private LocalDateTime auditAt;

    /**
     * AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)
     *
     * <p>取值定义见 {@link com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum}。
     *
     * <p>本类同时服务于「作者看自己的发帖」与管理端的待审队列，
     * 因此作者也能看到 AI 的初判结论——这是<b>刻意的透明度</b>：
     * 对作者来说，「AI 认为需要人工复核」远比只显示「待审」更有信息量，
     * 他据此知道内容在正常流转、而不是被静默丢弃。
     * 且这些结论涉及的都是<b>他自己的</b>内容，不构成他人信息泄露。
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

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

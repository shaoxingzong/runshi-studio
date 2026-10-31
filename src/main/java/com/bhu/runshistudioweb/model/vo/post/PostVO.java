package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（作者看自己的，含待审与驳回）
 *
 * authorId / authorName 只服务管理端：管理员要能定位到「这是谁发的」，否则筛选与核实身份都无从下手。
 *
 * @author shaoshing
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

    /** 作者昵称（管理端列表展示） */
    private String authorName;

    /** 帖子标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面图 URL */
    private String coverImage;

    /** 审核状态：0-待审, 1-已通过, 2-已驳回 */
    private Integer status;

    /** 驳回理由（仅"已驳回"时有意义，给用户看） */
    private String rejectReason;

    /** 审核时间（待审时为 null） */
    private LocalDateTime auditAt;

    /** AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工) */
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

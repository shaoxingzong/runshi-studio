package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评论视图对象（楼中楼，游客可获取）
 *
 * @author shaoshing
 */
@Data
public class PostCommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评论 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属帖子 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long postId;

    /** 评论人 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 评论人名（成员姓名；由 Service 批量查出后填充） */
    private String authorName;

    /** 评论人头像 URL（成员档案里的头像；未上传或查不到档案时为空） */
    private String authorAvatar;

    /** 父评论 ID：null 表示直接评论帖子 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /**
     * 被回复的评论 ID（= parentId，非空时表示这是一条回复）。
     * 与 parentId 分开命名是为了前端语义清晰：parentId 决定树结构，replyToId 决定「@ 谁」。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long replyToId;

    /** 被回复者展示名（由 Service 批量填充；父评论查不到或已删除时为空，前端不显示 @） */
    private String replyToName;

    /** 评论正文（纯文本） */
    private String content;

    /** 楼层号：顶层评论从 1 递增；回复某条评论时为 null */
    private Integer floor;

    /** 评论时间 */
    private LocalDateTime createdAt;

    /** 回复列表（楼中楼，只有两层；回复的回复仍挂在同一顶层下） */
    private List<PostCommentVO> children = new ArrayList<>();
}

package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子详情视图对象（C 端详情使用，游客可获取）
 *
 * @author shaoshing
 */
@Data
public class PostFrontDetailVO implements Serializable {

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

    /** 正文（Markdown，图片内嵌其中） */
    private String content;

    /** 作者 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 作者名 */
    private String authorName;

    /** 作者头像 URL（成员档案里的头像；未上传或查不到档案时为空） */
    private String authorAvatar;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数 */
    private Integer commentCount;

    /** 点赞数（冗余列，由点赞时原子自增维护） */
    private Integer likeCount;

    /** 当前登录用户是否已赞过本篇（游客恒为 false） */
    private Boolean liked;

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（C 端列表使用，游客可获取）
 *
 * @author shaoshing
 */
@Data
public class PostFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 帖子标题 */
    private String title;

    /** 摘要（为空时前端可用正文兜底，但 Service 入库时会尽量补上） */
    private String summary;

    /** 封面图 URL（列表缩略图） */
    private String coverImage;

    /** 作者 ID（关联 sys_user.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 作者名（成员姓名；由 Service 批量查出后填充，不做逐条查询） */
    private String authorName;

    /** 作者头像 URL（成员档案里的头像；查不到档案或未上传时为空） */
    private String authorAvatar;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数（冗余列） */
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

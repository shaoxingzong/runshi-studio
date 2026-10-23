package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

// 帖子详情视图对象（C 端详情使用，游客可获取）
//
// 与 PostFrontVO 的唯一实质区别是带上 content——
// 列表不传大字段、详情才传，这是响应体积与体验的取舍（见 com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO 的注释）。
// 其余脱敏口径完全一致：不含驳回理由、不含审核过程、不含审计字段。
//
// 安全提示（前端必须配合）： content 是 Markdown，
// 渲染链路必须是「marked → DOMPurify 白名单清洗 → v-html」，
// 且清洗时要额外限制 img 的 src 只能是站内路径
// （现有 ProjectDetail.vue 的清洗配置没有限制 src，帖子是成员发的、比管理员录入更不可信，需要补上这一步）。
@Data
public class PostFrontDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 帖子 ID（序列化为字符串）
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    // 帖子标题
    private String title;

    // 摘要
    private String summary;

    // 封面图 URL
    private String coverImage;

    // 正文（Markdown，图片内嵌其中）
    //
    // 它是本 VO 存在的理由：列表 VO 刻意不带它。
    private String content;

    // 作者 ID
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    // 作者名
    private String authorName;

    // 浏览量
    private Integer viewCount;

    // 评论数
    private Integer commentCount;

    // 点赞数（冗余列，由点赞时原子自增维护）
    private Integer likeCount;

    // 当前登录用户是否已赞过本篇（游客恒为 false）
    //
    // 与 PostFrontVO#getLiked 同义：带「当前用户」语义，不可被缓存。
    private Boolean liked;

    // 是否置顶
    private Integer pinned;

    // 发布时间
    private LocalDateTime createdAt;
}

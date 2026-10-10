package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子详情视图对象（<b>C 端详情</b>使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p>与 {@link PostFrontVO} 的唯一实质区别是<b>带上 {@code content}</b>——
 * 列表不传大字段、详情才传，这是响应体积与体验的取舍（见 {@link ProjectFrontVO} 的注释）。
 * 其余脱敏口径完全一致：不含驳回理由、不含审核过程、不含审计字段。
 *
 * <p><b>安全提示（前端必须配合）</b>：{@code content} 是 Markdown，
 * 渲染链路必须是「marked → DOMPurify 白名单清洗 → v-html」，
 * 且清洗时要额外限制 {@code img} 的 {@code src} 只能是站内路径
 * （现有 {@code ProjectDetail.vue} 的清洗配置没有限制 src，帖子是成员发的、比管理员录入更不可信，需要补上这一步）。
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

    /**
     * 正文（Markdown，图片内嵌其中）
     *
     * <p>它是本 VO 存在的理由：列表 VO 刻意不带它。
     */
    private String content;

    /** 作者 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 作者名 */
    private String authorName;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数 */
    private Integer commentCount;

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

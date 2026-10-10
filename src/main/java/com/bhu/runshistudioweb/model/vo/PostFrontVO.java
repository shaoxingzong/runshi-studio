package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（<b>C 端列表</b>使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p><b>脱敏靠「目标 VO 没有这些字段」实现</b>（与 {@link ProjectFrontVO} 同一套路）：
 * 本类刻意不包含：
 * <ul>
 *     <li><b>{@code content}</b>——Markdown 大字段。列表若带上它，每多一页就多传几十 KB 正文，
 *     只有详情接口（{@link PostFrontDetailVO}）才取；</li>
 *     <li><b>{@code rejectReason}</b>——驳回理由是<b>作者私事</b>，不该出现在公开列表里；</li>
 *     <li><b>{@code auditBy / auditAt}</b>——内部审核过程，与读者无关；</li>
 *     <li>审计字段（{@code createdBy / updatedBy / deletedAt}）。</li>
 * </ul>
 * {@code BeanUtils.copyProperties} 只拷「两边都存在」的属性，所以它们结构上出不去。
 *
 * <p><b>为什么不含 {@code status}</b>：列表查询已经过滤了「仅已通过」，
 * 返回的状态恒为 1，带上它只会让前端多一个无意义的判断分支。
 * 需要看状态的地方走「我的发帖」接口（{@link PostVO}）。
 */
@Data
public class PostFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 帖子 ID（雪花算法 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 帖子标题 */
    private String title;

    /** 摘要（为空时前端可用正文兜底，但 Service 入库时会尽量补上） */
    private String summary;

    /** 封面图 URL（列表缩略图） */
    private String coverImage;

    /**
     * 作者 ID（关联 sys_user.id）
     *
     * <p>与 {@link ProjectFrontVO} 去掉 {@code leaderId} 不同，这里的作者信息属于展示的一部分
     * （帖子天然要署名），且它是用户 ID 而非内部档案主键，因此保留。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 作者名（成员姓名；由 Service 批量查出后填充，不做逐条查询） */
    private String authorName;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数（冗余列） */
    private Integer commentCount;

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

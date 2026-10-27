package com.bhu.runshistudioweb.model.vo.post;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图对象（C 端列表使用，游客可获取）
 *
 * 脱敏靠「目标 VO 没有这些字段」：刻意不含 content（Markdown 大字段，列表每多一页就多传几十 KB 正文，
 * 只有详情 VO 才取）、rejectReason（作者私事）、auditBy / auditAt（内部审核过程）与审计字段。
 * BeanUtils.copyProperties 只拷「两边都存在」的属性，所以它们结构上出不去。
 *
 * 不含 status：列表查询已经过滤了「仅已通过」，返回的状态恒为 1，带上它只会让前端多一个无意义的判断分支；
 * 需要看状态的地方走「我的发帖」接口（PostVO）。
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

    /**
     * 作者 ID（关联 sys_user.id）
     *
     * 与 ProjectFrontVO 去掉 leaderId 不同，这里的作者信息属于展示的一部分（帖子天然要署名），
     * 且它是用户 ID 而非内部档案主键，因此保留。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    /** 作者名（成员姓名；由 Service 批量查出后填充，不做逐条查询） */
    private String authorName;

    /**
     * 作者头像 URL（成员档案里的头像；查不到档案或未上传时为空）
     *
     * 与 authorName 来自同一张 studio_member 表、同一条 SQL 的同一批行，不必再查一次（列表页最忌讳 N+1）。
     * 为空是正常情况（未上传头像、或作者已离队只剩账号），前端用姓名首字兜底，不要因此隐藏整条署名。
     */
    private String authorAvatar;

    /** 浏览量 */
    private Integer viewCount;

    /** 评论数（冗余列） */
    private Integer commentCount;

    /** 点赞数（冗余列，由点赞时原子自增维护） */
    private Integer likeCount;

    /**
     * 当前登录用户是否已赞过本篇（游客恒为 false）
     *
     * 本类里唯一带「当前用户」语义的字段：同一个列表，不同登录用户拿到的 liked 不同，因此它不能被缓存
     * （CDN / 浏览器缓存需按此排除，否则会出现「别人显示已赞、我却点不了」）。
     */
    private Boolean liked;

    /** 是否置顶 */
    private Integer pinned;

    /** 发布时间 */
    private LocalDateTime createdAt;
}

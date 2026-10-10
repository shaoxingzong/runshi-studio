package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 评论视图对象（<b>楼中楼</b>，游客可获取）
 *
 * author: shaoshing
 *
 * <p><b>{@code children} 是树形结构</b>：顶层评论的 {@code parentId} 为 null 且挂着自己的回复，
 * 回复的 {@code children} 为空列表（只支持两层）。
 * 这个树由 Service 在内存里组装——一次查完整个帖子的评论后按 {@code parentId} 分组挂起来，
 * <b>不是逐条查询</b>（那会是 N+1）。
 *
 * <p><b>脱敏</b>（与其它 VO 同一套路，靠「本类没有这些字段」实现）：
 * 不含 {@code status}、{@code rejectReason}、{@code auditBy/auditAt}、
 * {@code deletedBy} 以及审计字段。
 * 公开接口只返回「已通过」的评论，所以前端拿到的每条都是可展示的，
 * 不需要再根据状态过滤一遍。
 *
 * <p><b>为什么保留 {@code authorId}</b>：前端要能点击作者、或做「我的评论」高亮。
 * 它是用户 ID（sys_user.id）而非内部档案主键，属于可公开的信息。
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

    /**
     * 父评论 ID：null 表示直接评论帖子
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 评论正文（纯文本） */
    private String content;

    /**
     * 楼层号：顶层评论从 1 递增；回复某条评论时为 null
     *
     * <p>只有顶层才有楼层——回复依附于父评论，给它编号会让「第几楼」的语义变得混乱。
     */
    private Integer floor;

    /** 评论时间 */
    private LocalDateTime createdAt;

    /**
     * 回复列表（楼中楼，只有两层；回复的回复仍挂在同一顶层下）
     *
     * <p>默认给空列表而不是 null：前端直接 {@code v-for} 即可，不用先判空。
     */
    private List<PostCommentVO> children = new ArrayList<>();
}

package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发表评论请求
 *
 * author: shaoshing
 *
 * <p><b>{@code content} 的上限与列宽一致</b>（varchar(1000)）：
 * 「Service 校验长度」与「数据库列宽」用同一个值，就不会出现
 * 代码放行了 1200 字、入库时被严格模式拒绝的错位。
 * 这与帖子正文（text 列）由 Service 单独校验长度是不同的处理方式，原因就是列类型不同。
 *
 * <p><b>{@code parentId} 选填</b>：不填表示直接评论帖子（顶层，有楼层号）；
 * 填了表示回复某条评论（楼中楼，楼层号为 null）。
 * 只支持两层——回复的回复仍挂在同一个顶层下，不再继续嵌套。
 *
 * <p>注意：本 DTO<b>不接受 {@code status}</b>，理由与 {@link PostAddRequest} 相同——
 * 审核状态由服务端决定，先审后发一律先落「待审」。
 */
@Data
public class PostCommentAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属帖子 ID（必填）
     *
     * <p>必须是<b>已通过审核</b>的帖子——对待审/已驳回的帖子评论没有意义，
     * 由 Service 校验（A0402）。
     */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 必须为正整数")
    private Long postId;

    /**
     * 评论正文（必填，≤1000，与列宽一致）
     *
     * <p>评论是纯文本，不支持 Markdown：楼层里的富文本会显著增加渲染与清洗成本，
     * 而评论的价值主要在「说一句话」，不需要排版。
     */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 个字符")
    private String content;

    /**
     * 父评论 ID（选填）：回复某条评论时填；直接评论帖子时不填
     */
    private Long parentId;
}

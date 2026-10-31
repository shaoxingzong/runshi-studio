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
 * @author shaoshing
 */
@Data
public class PostCommentAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属帖子 ID（必填）
     *
     * 必须是已通过审核的帖子——对待审、已驳回的帖子评论没有意义，由 Service 校验（A0402）。
     */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 必须为正整数")
    private Long postId;

    /** 评论正文（必填，≤1000，与列宽一致） */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 个字符")
    private String content;

    /** 父评论 ID（选填）：回复某条评论时填；直接评论帖子时不填 */
    private Long parentId;
}

package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 整章移到另一本教材（管理端）
 *
 * @author shaoshing
 */
@Data
public class QuizChapterMoveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源教材名称（必填） */
    @NotBlank(message = "来源教材不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String fromTextbook;

    /** 章节名称（必填） */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;

    /** 目标教材名称（必填） */
    @NotBlank(message = "目标教材不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String toTextbook;

    /** 目标教材的顺序值（选填；null 表示沿用原值） */
    private Integer toTextbookSeq;
}

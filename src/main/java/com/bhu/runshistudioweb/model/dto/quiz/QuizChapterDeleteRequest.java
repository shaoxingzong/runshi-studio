package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 整章删除请求（管理端）
 *
 * @author shaoshing
 */
@Data
public class QuizChapterDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填） */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 章节名称（必填） */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;
}

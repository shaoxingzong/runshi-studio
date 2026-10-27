package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 整章移到另一本教材（管理端）
 *
 * 目标教材下已有同名章节时合并（提示管理员）而不报错，否则用户得先把两章改成不同名字再移。
 *
 * 移动后 textbook_seq 要跟着改：章节顺序是「教材内」的概念，换了教材就得用新教材的顺序值，
 * 否则排序会错乱。
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

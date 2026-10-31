package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 新增题目请求（管理端）
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填，≤128） */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 教材顺序（数值越小越靠前）；不传按 0 处理 */
    private Integer textbookSeq;

    /** 章节名称（必填，≤128） */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;

    /** 章节在教材内的顺序（数值越小越靠前）；不传按 0 处理 */
    private Integer chapterSeq;

    /** 题型：0-单选，1-多选（必填） */
    @NotNull(message = "题型不能为空")
    private Integer type;

    /** 题干（必填，≤2000，纯文本） */
    @NotBlank(message = "题干不能为空")
    @Size(max = 2000, message = "题干不能超过 2000 个字符")
    private String stem;

    /** 选项列表（必填，2–6 个；多选至少 3 个） */
    @NotNull(message = "选项不能为空")
    @Size(min = 2, max = 6, message = "选项数量需在 2 ~ 6 个之间")
    private List<QuizOptionItem> options;

    /** 正确选项标签（必填，升序无分隔，如 "B" / "ABD"） */
    @NotBlank(message = "正确答案不能为空")
    @Size(max = 8, message = "正确答案最多 6 个标签")
    private String correctLabels;

    /** 解析（选填，≤2000） */
    @Size(max = 2000, message = "解析不能超过 2000 个字符")
    private String analysis;

    /** 难度：1-易，2-中，3-难；不传按 1 处理 */
    private Integer difficulty;

    /** 题源（选填，≤128） */
    @Size(max = 128, message = "题源不能超过 128 个字符")
    private String source;

    /** 题序（数值越小越靠前）；不传按 0 处理 */
    private Integer questionSeq;
}

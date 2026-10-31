package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交作答请求（C 端）
 *
 * @author shaoshing
 */
@Data
public class QuizAnswerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 批次 ID（必填，由开批接口返回） */
    @NotNull(message = "批次 id 不能为空")
    @Positive(message = "批次 id 必须为正整数")
    private Long batchId;

    /** 题目 ID（必填） */
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long questionId;

    /** 所选标签（必填，如 "AC"；单字符亦可） */
    @NotBlank(message = "请选择答案")
    @Size(max = 8, message = "答案最多 6 个标签")
    private String chosenLabels;
}

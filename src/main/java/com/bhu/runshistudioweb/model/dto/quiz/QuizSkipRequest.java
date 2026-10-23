package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

// 跳过题目请求（C 端）
//
// 为什么跳过要单独一个接口，而不是「提交一个空答案」：
// - 语义不同：空答案会被判错，而跳过不是错——它不写错题本；
// - 响应不同：提交返回答案与解析，跳过什么都不返回。
// 若复用提交接口，「跳过」就成了看答案的捷径，顺序模式会被刷穿；
// - 幂等键相同：两者都走 uk_user_batch_question，
// 所以「先跳过、再回来答」在同一批里是不允许的——这是刻意的，
// 否则用户可以先跳过看进度、再回头「第一次」作答。
@Data
public class QuizSkipRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    // 批次 ID（必填）
    @NotNull(message = "批次 id 不能为空")
    @Positive(message = "批次 id 必须为正整数")
    private Long batchId;

    // 题目 ID（必填）
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long questionId;
}

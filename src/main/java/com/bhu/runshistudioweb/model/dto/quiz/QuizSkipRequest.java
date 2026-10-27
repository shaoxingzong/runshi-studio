package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 跳过题目请求（C 端）
 *
 * 跳过必须单独一个接口，不能复用「提交空答案」：空答案会被判错，而跳过不是错（不写错题本），
 * 且提交会返回答案与解析，复用后「跳过」就成了看答案的捷径，顺序模式会被刷穿。
 *
 * 幂等键与作答相同（uk_user_batch_question），所以同一批里「先跳过、再回来答」是不允许的。
 *
 * @author shaoshing
 */
@Data
public class QuizSkipRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 批次 ID（必填） */
    @NotNull(message = "批次 id 不能为空")
    @Positive(message = "批次 id 必须为正整数")
    private Long batchId;

    /** 题目 ID（必填） */
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long questionId;
}

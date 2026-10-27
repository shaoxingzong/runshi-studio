package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 启用 / 停用题目请求
 *
 * 停用与删除的分工：两者都不能被抽到、错题本里也都看不到，但停用可恢复（重新启用后条目自动回来），
 * 删除不可恢复；两者都不影响历史作答。
 *
 * 因此「改坏了想临时下线」用停用，只有「这题彻底不要了」才删除。
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID（必填） */
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long id;

    /** 目标状态：0-启用，1-停用（必填） */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0（启用）或 1（停用）")
    @Max(value = 1, message = "状态只能是 0（启用）或 1（停用）")
    private Integer status;
}

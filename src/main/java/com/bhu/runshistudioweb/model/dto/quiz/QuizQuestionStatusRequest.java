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
 * author: shaoshing
 *
 * <p><b>停用与删除的分工</b>（管理端最需要理解的一对概念）：
 * <table border="1">
 *     <caption>停用 vs 删除</caption>
 *     <tr><th></th><th>停用</th><th>删除</th></tr>
 *     <tr><td>还能被抽到</td><td>否</td><td>否</td></tr>
 *     <tr><td>错题本里还能看到</td><td><b>不能</b>（查询过滤，条目保留）</td><td>不能</td></tr>
 *     <tr><td>可以恢复</td><td><b>是</b>（重新启用，条目自动回来）</td><td>否</td></tr>
 *     <tr><td>对历史作答</td><td>无影响</td><td>无影响</td></tr>
 * </table>
 *
 * <p>因此「改坏了想临时下线」应该用停用，只有「这题彻底不要了」才删除。
 */
@Data
public class QuizQuestionStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 题目 ID（必填）
     */
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long id;

    /**
     * 目标状态：0-启用，1-停用（必填）
     */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0（启用）或 1（停用）")
    @Max(value = 1, message = "状态只能是 0（启用）或 1（停用）")
    private Integer status;
}

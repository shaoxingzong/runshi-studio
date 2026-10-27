package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 手动「标记已掌握」请求（C 端）
 *
 * 与「答对自动消解」是两条路径，靠 resolve_type（1-自动 / 2-手动）区分，两者可信度不同。
 * 幂等：已是已消解时直接返回 true 且不更新 resolved_at，重复点击不该改掉掌握时间。
 *
 * @author shaoshing
 */
@Data
public class QuizWrongResolveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 错题本条目的主键 ID（不是题目 ID）
     *
     * 用条目 ID 让「这条错题」成为唯一主语，越权校验（条目.userId == 当前用户）也更直接。
     */
    @NotNull(message = "错题 id 不能为空")
    @Positive(message = "错题 id 必须为正整数")
    private Long id;
}

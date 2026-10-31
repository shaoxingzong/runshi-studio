package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 手动「标记已掌握」请求（C 端）
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

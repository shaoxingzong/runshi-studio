package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 手动「标记已掌握」请求（C 端）
 *
 * 它与「答对自动消解」是两条不同的路径，记录在
 * resolve_type（1-自动 / 2-手动）里——两者的可信度不同，
 * 将来若要做掌握度评估，这个字段就是区分依据。
 *
 * 幂等：已经是已消解时直接返回 true，不更新 resolved_at——
 * 重复点击不该把「什么时候掌握的」这个时间点改掉。
 *
 * @author shaoshing
 */
@Data
public class QuizWrongResolveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 错题本条目的主键 ID（不是题目 ID）
     *
     * 用条目 ID 而不是题目 ID 是为了让「这条错题」成为唯一主语：
     * 同一个人对同一题只有一条条目，但接口语义上它属于「我的错题本」，
     * 用条目 ID 时越权校验（条目.userId == 当前用户）也更直接。
     */
    @NotNull(message = "错题 id 不能为空")
    @Positive(message = "错题 id 必须为正整数")
    private Long id;
}

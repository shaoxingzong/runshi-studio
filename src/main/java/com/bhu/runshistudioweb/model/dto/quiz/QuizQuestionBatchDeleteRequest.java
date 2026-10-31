package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量删除题目请求
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionBatchDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待删除的题目 ID 列表（必填，非空） */
    @NotNull(message = "题目 id 列表不能为空")
    private List<Long> ids;
}

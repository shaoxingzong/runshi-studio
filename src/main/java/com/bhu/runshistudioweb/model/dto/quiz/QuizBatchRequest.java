package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 开一批题目的请求（C 端）
 *
 * @author shaoshing
 */
@Data
public class QuizBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模式：0-顺序刷题，1-错题重刷，2-随机刷题（必填） */
    @NotNull(message = "刷题模式不能为空")
    private Integer mode;

    /** 教材名称（顺序模式必填；其余模式选填 = 范围过滤） */
    private String textbookName;

    /** 章节名称（顺序模式必填；其余模式选填 = 范围过滤） */
    private String chapterName;

    /** 本轮题量；不传用 studio.quiz.default-size，超过上限收敛到上限 */
    private Integer size;

    /** 顺序模式下是否「重新刷本章」 */
    private Boolean restart;
}

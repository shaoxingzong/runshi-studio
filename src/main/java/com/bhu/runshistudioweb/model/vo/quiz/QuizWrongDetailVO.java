package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 错题详情（含答案与解析）
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuizWrongDetailVO extends QuizWrongItemVO {

    private static final long serialVersionUID = 1L;

    /** 选项列表（仍不含正确性：正确性由 correctLabels 表达，前端自己按 label 匹配即可着色，不需要选项自带 isCorrect） */
    private List<QuizOptionVO> options;
    /** 正确选项标签（如 "ABD"） */
    private String correctLabels;

    /** 解析（可空） */
    private String analysis;

    /** 该用户最近一次答错时所选标签（可能为 null：更早的数据或跳过） */
    private String lastChosenLabels;
}

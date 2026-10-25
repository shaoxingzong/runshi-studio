package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 错题详情（含答案与解析）
 *
 * 为什么这里可以有答案：能进错题本的前提是「这道题你做错过」，
 * 做错过的人看到正确答案本来就是应该的——这正是错题本存在的意义。
 * 列表 QuizWrongItemVO 则刻意不带答案（见它的类注释）。
 *
 * lastChosenLabels 是「你上次选了什么」，用于错题回顾时对照：
 * 只显示正确答案的话，用户不知道自己错在哪。
 * 它取自该用户对该题最近一条 result=2 的记录。
 *
 * @author shaoshing
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuizWrongDetailVO extends QuizWrongItemVO {

    private static final long serialVersionUID = 1L;

    /**
     * 选项列表（仍不含正确性：正确性由 correctLabels 表达，
     * 前端自己按 label 匹配即可着色，不需要选项自带 isCorrect）
     */
    private List<QuizOptionVO> options;

    /** 正确选项标签（如 "ABD"） */
    private String correctLabels;

    /** 解析（可空） */
    private String analysis;

    /** 该用户最近一次答错时所选标签（可能为 null：更早的数据或跳过） */
    private String lastChosenLabels;
}

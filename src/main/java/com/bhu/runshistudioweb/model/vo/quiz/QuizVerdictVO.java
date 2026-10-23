package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 判定结果（C 端）—— 答案与解析在本模块唯一的下发出口
 *
 * 它只在 POST /quiz/answer 成功之后产生，且只针对刚刚提交的那一题。
 * 这与「题目 VO 不带答案」是一对配套设计：
 * 答题前拿不到答案，答题后只拿到这一题的答案——不存在「一次拿到整批答案」的路径。
 *
 * wrongBookAction 是本 VO 里唯一与错题本相关的字段，
 * 前端据此给不同的提示（「已加入错题本」/「再答对 1 次即消解」/「已消解」）。
 * 它的取值是字符串而不是数字：前端拿它做 switch 分支，字符串可读性远好于魔法数字，
 * 且它不落库、不参与统计，没有「必须与 DDL 同源」的约束。
 */
@Data
public class QuizVerdictVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;

    /** 题型：0-单选，1-多选（前端渲染「正确答案 ABD」时按题型决定措辞） */
    private Integer type;

    /**
     * 判定结果：1-正确，2-错误
     *
     * 本 VO 不会返回 3（跳过）——跳过走另一个接口，且不返回答案。
     */
    private Integer result;

    /** 用户所选标签（归一化后的升序串，如 "AC"） */
    private String chosenLabels;

    /** 正确选项标签（如 "ABD"；单选为单字符） */
    private String correctLabels;

    /** 解析（可空） */
    private String analysis;

    /**
     * 本题对错题本的影响：
     * - added：首次答错，已加入错题本；
     * - reopened：之前在错题本里已消解，这次又答错，已回到待复习；
     * - progress：在错题本里答对但还没达到消解阈值（前端提示「再答对 N 次即消解」）；
     * - resolved：在错题本里连对达标，已消解；
     * - none：不在错题本里，或答对但本来就不在错题本里（无副作用）。
     */
    private String wrongBookAction;

    /**
     * 该题在错题本里还差几次消解（仅 progress 时有意义，其余为 null）
     *
     * 单独给一个数字而不是让前端拼文案：文案要随阈值配置变化，
     * 前端拼会把 resolve-streak 的值复制一份到前端。
     */
    private Integer remainingStreak;

    /** 累计作答（不含跳过，含重做）：前端用它即时刷新「学习概览」 */
    private Integer answeredTotal;

    /** 累计答对：与 answeredTotal 一起让前端算出正确率，不必再调一次概览接口 */
    private Integer correctTotal;
}

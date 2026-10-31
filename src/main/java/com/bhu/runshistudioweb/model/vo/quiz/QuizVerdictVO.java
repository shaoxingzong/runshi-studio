package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 判定结果（C 端）—— 答案与解析在本模块唯一的下发出口
 *
 * @author shaoshing
 */
@Data
public class QuizVerdictVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;

    /** 题型：0-单选，1-多选（前端渲染「正确答案 ABD」时按题型决定措辞） */
    private Integer type;

    /** 判定结果：1-正确，2-错误 */
    private Integer result;

    /** 用户所选标签（归一化后的升序串，如 "AC"） */
    private String chosenLabels;

    /** 正确选项标签（如 "ABD"；单选为单字符） */
    private String correctLabels;

    /** 解析（可空） */
    private String analysis;

    /** 本题对错题本的影响：- added：首次答错，已加入错题本； */
    private String wrongBookAction;

    /** 该题在错题本里还差几次消解（仅 progress 时有意义，其余为 null） */
    private Integer remainingStreak;
    /** 累计作答（不含跳过，含重做）：前端用它即时刷新「学习概览」 */
    private Integer answeredTotal;

    /** 累计答对：与 answeredTotal 一起让前端算出正确率，不必再调一次概览接口 */
    private Integer correctTotal;
}

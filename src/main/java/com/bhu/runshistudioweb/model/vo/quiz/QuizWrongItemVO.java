package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 错题本列表项（不含答案）
 *
 * @author shaoshing
 */
@Data
public class QuizWrongItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 错题本条目 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 题目 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;

    /** 题型：0-单选，1-多选（列表上要能看出「这是多选」） */
    private Integer type;

    /** 题干（前端两行截断展示） */
    private String stem;

    /** 教材名称（便于按来源分组展示） */
    private String textbookName;

    /** 章节名称 */
    private String chapterName;

    /** 状态：0-待复习，1-已消解 */
    private Integer status;

    /** 累计答错次数 */
    private Integer wrongCount;

    /** 当前连对进度（达到阈值即自动消解） */
    private Integer correctStreak;

    /** 最近答错时间 */
    private LocalDateTime lastWrongAt;

    /** 消解时间（未消解为 null） */
    private LocalDateTime resolvedAt;

    /** 消解方式：1-自动，2-手动（未消解为 null） */
    private Integer resolveType;
}

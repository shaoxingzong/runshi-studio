package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端题目视图（<b>含选项与答案</b>，仅 admin）
 *
 * author: shaoshing
 *
 * <p>与 C 端的 {@link QuizQuestionVO} 是<b>两个类</b>，靠「VO 里有没有这个字段」实现脱敏
 * （HELP.md 第 10 条：C 端 VO 与管理端 VO 分成两个类，绝不复用）。
 * 管理端要编辑题目，必须看到答案；C 端绝不能看到。
 *
 * <p>{@code answerCount} / {@code accuracy} 是列表页要展示的两个派生列：
 * 管理员靠它们判断「这道题是不是出得太难/太怪」。它们由 Service 批量查一次
 * {@code GROUP BY question_id} 填充，<b>不是逐行 count</b>（那是 N+1）。
 */
@Data
public class QuizQuestionAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 教材名称 */
    private String textbookName;

    /** 教材顺序 */
    private Integer textbookSeq;

    /** 章节名称 */
    private String chapterName;

    /** 章节顺序 */
    private Integer chapterSeq;

    /** 题型：0-单选，1-多选 */
    private Integer type;

    /** 题干 */
    private String stem;

    /** 选项列表 */
    private List<QuizOptionVO> options;

    /** 正确选项标签 */
    private String correctLabels;

    /** 解析 */
    private String analysis;

    /** 难度：1-易，2-中，3-难 */
    private Integer difficulty;

    /** 题源 */
    private String source;

    /** 题序 */
    private Integer questionSeq;

    /** 状态：0-启用，1-停用 */
    private Integer status;

    /** 累计作答数（仅统计 result IN (1,2)，不含跳过） */
    private Integer answerCount;

    /** 正确率（0~1）；作答数为 0 时为 null */
    private Double accuracy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

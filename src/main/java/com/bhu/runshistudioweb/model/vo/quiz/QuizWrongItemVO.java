package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 错题本列表项（不含答案）
 *
 * 列表不带答案，详情才带：错题本列表可能一次展示几十条，
 * 每条都带上正确项与解析，等于把整页答案一次性交给客户端——
 * 用户完全可以在答题时另开一个标签页翻错题本。
 *
 * 详情 QuizWrongDetailVO 带答案的前提是「这道题你已经做错过」，
 * 这是合理的：做错过的人看到答案本来就是应该的。
 * 但「浏览列表」这个动作不该泄露答案，所以两个 VO 刻意分开。
 *
 * id 是错题本条目的主键，不是题目 ID——两者都要给：
 * 详情/消解用条目 ID，重新出题用题目 ID。
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

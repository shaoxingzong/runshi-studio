package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 题目视图对象（C 端答题时使用）
 *
 * 本类结构上没有 correctLabels 与 analysis，
 * 这是本模块的安全底线，任何「顺手加个字段」的改动都必须被拒绝。
 *
 * 理由：判分必须在服务端完成。一旦题目 VO 带上答案，
 * 用户在浏览器里就能看到正确答案，整套刷题功能失去意义。
 * 脱敏靠结构而不是靠「查出来再删」——后者只要有一处忘了过滤就是事故，
 * 而前者在编译期就不给你这个字段。
 *
 * 答案与解析只在 QuizVerdictVO 里出现，且只在提交成功之后产生。
 * 错题详情 QuizWrongDetailVO 也带答案，前提是这道题该用户已经做错过。
 *
 * inWrongBook 只表示「这题在我的错题本里」，不含任何答案信息——
 * 它是给前端做「这题你错过」标记用的，不能用来推断正确项。
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 题型：0-单选，1-多选（前端据此决定 radio 还是 checkbox 交互） */
    private Integer type;

    /** 题干（纯文本，前端用插值渲染，不走 Markdown 管线） */
    private String stem;

    /** 选项列表（不含正确性） */
    private List<QuizOptionVO> options;

    /** 该题当前是否在本人的错题本里（只给标记，不泄露答案） */
    private Boolean inWrongBook;
}

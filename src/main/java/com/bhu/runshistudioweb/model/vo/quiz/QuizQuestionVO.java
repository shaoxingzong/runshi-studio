package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 题目视图对象（C 端答题时使用）
 *
 * inWrongBook 只表示「这题在我的错题本里」，不含任何答案信息，不能用来推断正确项。
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

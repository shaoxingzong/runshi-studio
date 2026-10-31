package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 章节视图对象（C 端「章节」下拉的数据源）
 *
 * @author shaoshing
 */
@Data
public class QuizChapterVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 章节名称（既是显示名，也是提交时回传的值） */
    private String name;

    /** 章节在教材内的顺序（数值越小越靠前） */
    private Integer seq;

    /** 该章节下启用的题目数 */
    private Integer questionCount;
}

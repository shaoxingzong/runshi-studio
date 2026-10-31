package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 教材视图对象（C 端「教材」下拉的数据源）
 *
 * @author shaoshing
 */
@Data
public class QuizTextbookVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（既是显示名，也是提交时回传的值） */
    private String name;

    /** 教材顺序（数值越小越靠前） */
    private Integer seq;

    /** 该教材下的题目总数（含停用题：管理员需要看到真实体量） */
    private Integer questionCount;

    /** 章节列表（按 chapterSeq 升序） */
    private List<QuizChapterVO> chapters;
}

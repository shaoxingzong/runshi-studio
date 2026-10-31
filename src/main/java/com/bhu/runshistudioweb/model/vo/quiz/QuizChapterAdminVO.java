package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端「章节」视图（含停用题，与 C 端 QuizChapterVO 口径不同）
 *
 * @author shaoshing
 */
@Data
public class QuizChapterAdminVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称 */
    private String textbookName;

    /** 教材顺序 */
    private Integer textbookSeq;

    /** 章节名称 */
    private String name;

    /** 章节顺序 */
    private Integer seq;

    /** 该章题目总数（含停用） */
    private Integer questionCount;

    /** 其中启用的题数 */
    private Integer enabledCount;
}

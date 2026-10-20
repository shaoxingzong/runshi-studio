package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端「章节」视图（含停用题，与 C 端 {@link QuizChapterVO} 口径不同）
 *
 * author: shaoshing
 *
 * <p><b>为什么不复用 C 端的 {@link QuizChapterVO}</b>：
 * 两端的 {@code questionCount} 口径不一样——C 端只数启用题（用户看到的是「能刷多少」），
 * 管理端要数全部题（管理员看到的是「这一章有多少存量」）。
 * 共用同一个 VO 就必然有一端拿到的数字是错的，而这种错不会报错、只会让人困惑。
 *
 * <p>{@code enabledCount} 单独给一个字段，管理端就能显示「共 50 题（启用 48）」。
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

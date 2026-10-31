package com.bhu.runshistudioweb.model.dto.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 错题本分页查询请求（C 端）
 *
 * @author shaoshing
 */
@Data
public class QuizWrongQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（精确匹配，选填） */
    private String textbookName;

    /** 章节名称（精确匹配，选填；需配合 textbookName 使用） */
    private String chapterName;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛 */
    private Long pageSize = 10L;
}

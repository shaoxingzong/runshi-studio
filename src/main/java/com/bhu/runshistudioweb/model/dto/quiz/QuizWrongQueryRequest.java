package com.bhu.runshistudioweb.model.dto.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 错题本分页查询请求（C 端）
 *
 * 与其它查询 DTO 同一惯例：GET + 对象绑定，分页字段由本类声明。
 * 分页参数非法时由 Service 兜底纠正，不报错。
 *
 * 为什么支持按教材/章节筛选：错题积累到几十道之后，
 * 「只复习第一章的错题」是最常见的诉求；没有这个筛选，用户只能一页页翻。
 * 筛选靠 question_id IN (子查询) 实现——与「过滤停用题」共用同一段子查询，
 * 不会多一次查询。
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

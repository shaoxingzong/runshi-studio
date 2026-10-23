package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

// 章节视图对象（C 端「章节」下拉的数据源）
//
// 与 QuizTextbookVO 同理：章节没有独立表，本 VO 由
// GROUP BY textbook_name, chapter_name 生成，名字即标识。
//
// questionCount 只统计启用的题：它是给用户看「这一章有多少题可刷」的，
// 把停用题算进去会让用户点进去发现题数对不上。管理端的章节列表用另一个口径
// （含停用），两者刻意分开。
@Data
public class QuizChapterVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 章节名称（既是显示名，也是提交时回传的值）
    private String name;

    // 章节在教材内的顺序（数值越小越靠前）
    private Integer seq;

    // 该章节下启用的题目数
    private Integer questionCount;
}

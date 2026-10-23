package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

// 教材视图对象（C 端「教材」下拉的数据源）
//
// 教材没有独立表，本 VO 由一条 GROUP BY 生成：
// SELECT textbook_name, MAX(textbook_seq), COUNT(*) ... GROUP BY textbook_name。
// 因此 name 同时充当「显示名」与「下拉的 value」——
// 前端提交时回传的就是这个名字。
//
// 为什么用名字而不是 ID：教材的 ID 只会在题目表里冗余一份，
// 而「改教材名」本质上就是批量改这个字符串。用名字做标识让
// 「改名 / 合并」变成一条 UPDATE，不需要维护额外的 ID 映射。
// 代价是改名后前端缓存的旧名字会失效——但教材改名极少发生，
// 且前端每次进页面都会重新拉 catalog。
@Data
public class QuizTextbookVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 教材名称（既是显示名，也是提交时回传的值）
    private String name;

    // 教材顺序（数值越小越靠前）
    private Integer seq;

    // 该教材下的题目总数（含停用题：管理员需要看到真实体量）
    private Integer questionCount;

    // 章节列表（按 chapterSeq 升序）
    private List<QuizChapterVO> chapters;
}

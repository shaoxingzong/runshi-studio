package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端题目分页查询请求（GET + 对象绑定）
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（精确匹配；教材是分组名，模糊匹配会把「马原」和「马原习题集」混在一起） */
    private String textbookName;

    /** 章节名称（精确匹配） */
    private String chapterName;

    /** 题型（精确匹配）：0-单选，1-多选；非法值由 Service 返回 A0401 */
    private Integer type;

    /** 题干关键词（模糊匹配；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String keyword;

    /** 状态（精确匹配）：0-启用，1-停用；不传表示全部 */
    private Integer status;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛 */
    private Long pageSize = 10L;
}

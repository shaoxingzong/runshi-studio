package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 整章删除请求（管理端）
 *
 * 这是本模块唯一会批量删数据的接口，因此要求前端做二次确认
 * 并在确认框里显示「该章有 N 道题，其中 M 道有作答记录」——
 * 删完之后错题本里对应条目也会一并清理（题目没了，条目无处可去），
 * 但作答记录保留（统计只读 result，不需要题目内容）。
 */
@Data
public class QuizChapterDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填） */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 章节名称（必填） */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;
}

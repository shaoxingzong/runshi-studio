package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 整章删除请求（管理端）
 *
 * 本模块唯一会批量删数据的接口，前端须二次确认并显示「该章有 N 道题，其中 M 道有作答记录」。
 * 删除后错题本条目一并清理，但作答记录保留（统计只读 result，不需要题目内容）。
 *
 * @author shaoshing
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

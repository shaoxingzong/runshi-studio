package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 章节改名 / 调序请求（管理端）
 *
 * 章节没有独立表，它是题目上的两个名字列，随题目诞生，因此没有「新增」接口，
 * 管理端能做的只有改名 / 调序 / 移章 / 整章删除这四种批量操作。
 *
 * 改名是一条批量 UPDATE 而不是逐条：章节可能是几百道题的归属，逐条更新既慢又会中途失败留下半章。
 * 改名到已存在的章节名时合并并提示管理员，比报错「名称已存在」更符合直觉。
 *
 * @author shaoshing
 */
@Data
public class QuizChapterRenameRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填，定位章节所属教材） */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 原章节名称（必填） */
    @NotBlank(message = "原章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String oldName;

    /** 新章节名称（必填；与 oldName 相同时视为「只调序」） */
    @NotBlank(message = "新章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String newName;

    /** 新的章节顺序（选填；null 表示不修改顺序） */
    private Integer chapterSeq;
}

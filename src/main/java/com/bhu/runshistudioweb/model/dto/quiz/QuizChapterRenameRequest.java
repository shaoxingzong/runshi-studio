package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 章节改名 / 调序请求（管理端）
 *
 * 为什么章节没有「新增」接口：章节没有独立表，
 * 它是题目上的两个名字列。因此「章节」是随题目诞生的——
 * 往某章新增一道题，这一章就存在了。管理端能做的只有
 * 「改名 / 调序 / 移章 / 整章删除」这四种批量操作。
 *
 * 为什么改名是批量 UPDATE 而不是逐条：章节可能是几百道题的归属，
 * 逐条更新既慢又会中途失败留下半章。一条
 * UPDATE ... WHERE textbook_name=? AND chapter_name=? 在事务里原子完成。
 *
 * 改名到已存在的章节名时合并（两章并成一章），并提示管理员——
 * 这比报错「名称已存在」更符合直觉：用户想的大概率就是合并。
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

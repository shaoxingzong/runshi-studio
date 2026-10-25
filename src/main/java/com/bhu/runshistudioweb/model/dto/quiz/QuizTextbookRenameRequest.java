package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 教材改名 / 调序请求（管理端）
 *
 * 教材没有独立表，它就是题目上的 textbook_name + textbook_seq 两列。
 * 因此「改名」= 一条批量 UPDATE；改到已存在的名字时合并。
 *
 * @author shaoshing
 */
@Data
public class QuizTextbookRenameRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原教材名称（必填） */
    @NotBlank(message = "原教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String oldName;

    /** 新教材名称（必填；与 oldName 相同时视为「只调序」） */
    @NotBlank(message = "新教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String newName;

    /** 新的教材顺序（选填；null 表示不修改顺序） */
    private Integer textbookSeq;
}

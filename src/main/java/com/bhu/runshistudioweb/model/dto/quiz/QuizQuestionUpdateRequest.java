package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

// 修改题目请求（管理端，部分更新：字段为 null 表示不修改）
//
// 部分更新的坑，以及为什么必须「用旧值补齐后再整体校验」：
// 校验规则是跨字段的（单选恰好 1 个正确项、正确项必须属于选项……）。
// 如果只拿请求里传了的字段去校验，「只改解析」这条最常见的操作
// 会因为没传 options 而报「选项不能为空」——这是纯粹的误伤。
//
// 因此实现里先按 id 取出旧实体，把请求中为 null 的字段用旧值填上，
// 得到一份完整的待写状态，再走与新增完全相同的那套校验。
// 这样「只改解析」和「改答案」走的是同一条代码路径，不会有两套规则。
//
// 为什么没有 @NotBlank：本类的所有字符串字段都允许为 null（= 不改），
// 空串则会被视为「要改成空」并在整体校验阶段被拒——两者的区别必须保留。
@Data
public class QuizQuestionUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    // 题目 ID（必填）
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long id;

    // 教材名称（null 表示不修改）
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    // 教材顺序（null 表示不修改）
    private Integer textbookSeq;

    // 章节名称（null 表示不修改）
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;

    // 章节顺序（null 表示不修改）
    private Integer chapterSeq;

    // 题型：0-单选，1-多选（null 表示不修改）
    private Integer type;

    // 题干（null 表示不修改）
    @Size(max = 2000, message = "题干不能超过 2000 个字符")
    private String stem;

    // 选项列表（null 表示不修改；传了就整批替换）
    @Size(min = 2, max = 6, message = "选项数量需在 2 ~ 6 个之间")
    private List<QuizOptionItem> options;

    // 正确选项标签（null 表示不修改）
    @Size(max = 8, message = "正确答案最多 6 个标签")
    private String correctLabels;

    // 解析（null 表示不修改；空串表示清空）
    @Size(max = 2000, message = "解析不能超过 2000 个字符")
    private String analysis;

    // 难度（null 表示不修改）
    private Integer difficulty;

    // 题源（null 表示不修改；空串表示清空）
    @Size(max = 128, message = "题源不能超过 128 个字符")
    private String source;

    // 题序（null 表示不修改）
    private Integer questionSeq;

    // 状态：0-启用，1-停用（null 表示不修改）
    private Integer status;
}

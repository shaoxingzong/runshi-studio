package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

// 选项视图对象（C 端与错题详情共用）
//
// 本类最重要的性质：结构上没有 isCorrect。
// 这不是「查出来之后删掉了」，而是数据里根本就没有——
// 正确性存在题目的 correct_labels 列，选项 JSON 只含展示数据。
// 因此本 VO 可以被原样下发，不存在「忘了过滤」的泄露面。
//
// 与入参 QuizOptionItem 字段一致但刻意分成两个类：
// 入参未来可能加临时字段（如前端行 id），它们绝不该出现在响应里。
@Data
public class QuizOptionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 选项标签：A–F
    private String label;

    // 选项正文
    private String content;
}

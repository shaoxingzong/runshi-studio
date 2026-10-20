package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 单个选项（写入用）
 *
 * author: shaoshing
 *
 * <p><b>注意它<b>没有</b> {@code isCorrect} 字段</b>：正确性由题目的
 * {@code correctLabels} 统一表达，不在选项上逐个标记。
 *
 * <p>为什么这样拆：如果把「哪个是对的」放在选项里，那么「选项 JSON」本身就带了答案，
 * C 端 VO 每次下发都得记得过滤——漏一次就是答案泄露。把正确性提到
 * {@code correctLabels} 之后，选项 JSON <b>只含展示数据</b>，
 * 可以原样下发，脱敏靠数据结构而不是靠记忆。
 *
 * <p>它的读侧对应 {@code QuizOptionVO}（同样没有 isCorrect），
 * 两者字段完全一致但<b>刻意分成两个类</b>：
 * 入参未来可能加「临时 id」之类的东西，而它们绝不该出现在响应里。
 */
@Data
public class QuizOptionItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 选项标签：A–F（必须从 A 开始连续，跳号由 Service 校验）
     */
    @NotBlank(message = "选项标签不能为空")
    @Size(max = 1, message = "选项标签只能是单个字母")
    private String label;

    /**
     * 选项正文（≤500）
     */
    @NotBlank(message = "选项内容不能为空")
    @Size(max = 500, message = "选项内容不能超过 500 个字符")
    private String content;
}

package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 单个选项（写入用）
 *
 * @author shaoshing
 */
@Data
public class QuizOptionItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 选项标签：A–F（必须从 A 开始连续，跳号由 Service 校验） */
    @NotBlank(message = "选项标签不能为空")
    @Size(max = 1, message = "选项标签只能是单个字母")
    private String label;

    /** 选项正文（≤500） */
    @NotBlank(message = "选项内容不能为空")
    @Size(max = 500, message = "选项内容不能超过 500 个字符")
    private String content;
}

package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 批量导入题目请求（管理端）
 *
 * 用「粘贴文本」而不是文件上传：题库来源多是文档、网页里复制的一段题目，粘贴少两步，
 * 且文本解析的失败信息可以精确到行号。
 *
 * dryRun 供前端「校验」按钮先传 true 拿到解析结果与错误行号做预览，确认后再传 false 落库；
 * 真正导入时也是整批全成功或全失败，不会导入一半。
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionImportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填）：导入的题目全部归到这本教材下 */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 章节名称（必填）：导入的题目全部归到这一章下 */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;

    /** 教材顺序（选填，默认 0） */
    private Integer textbookSeq;

    /** 章节顺序（选填，默认 0） */
    private Integer chapterSeq;

    /**
     * 起始题序（选填，默认 0）：本批第一题的 questionSeq，
     * 后续每题依次 +1。便于「先导入第一章 0–49，再导入第二章 50–99」。
     */
    private Integer startSeq;

    /**
     * 待解析的文本（必填）
     *
     * 格式见 QuizImportParser 的类注释。长度不设注解上限，由 Service 按题目数量
     * （QuizConstant.IMPORT_MAX_QUESTIONS）拦截——用题目数比用字符数更贴近真实约束。
     */
    @NotBlank(message = "导入内容不能为空")
    private String text;

    /** 只校验不落库（默认 false） */
    private Boolean dryRun;
}

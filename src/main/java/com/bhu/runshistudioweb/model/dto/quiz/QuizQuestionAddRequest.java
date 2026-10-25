package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 新增题目请求（管理端）
 *
 * 为什么 options 是 List<QuizOptionItem> 而不是 JSON 字符串：
 * 让前端拼 JSON 字符串等于把序列化责任推给调用方，且后端无法用 @Valid
 * 逐项校验（选项正文长度、标签非空）。收成列表后，字段级校验自动生效，
 * 序列化由 Service 用容器里的 JsonMapper 完成（与 techStack 同一套路）。
 *
 * 为什么 type 与 correctLabels 没有 @Pattern 之类的注解：
 * 它们的约束是跨字段的（单选必须恰好 1 个正确项、多选至少 2 个、
 * 正确项必须是已有选项、标签必须连续），注解表达不了。
 * 这类校验统一放在 QuizQuestionServiceImpl#validateQuestion，
 * 好处是「新增 / 修改 / 导入」三条入口共用同一套规则，不会各写一份。
 *
 * @author shaoshing
 */
@Data
public class QuizQuestionAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 教材名称（必填，≤128） */
    @NotBlank(message = "教材名称不能为空")
    @Size(max = 128, message = "教材名称不能超过 128 个字符")
    private String textbookName;

    /** 教材顺序（数值越小越靠前）；不传按 0 处理 */
    private Integer textbookSeq;

    /** 章节名称（必填，≤128） */
    @NotBlank(message = "章节名称不能为空")
    @Size(max = 128, message = "章节名称不能超过 128 个字符")
    private String chapterName;

    /** 章节在教材内的顺序（数值越小越靠前）；不传按 0 处理 */
    private Integer chapterSeq;

    /**
     * 题型：0-单选，1-多选（必填）
     *
     * 合法性由 Service 用 QuizQuestionTypeEnum 校验，
     * 非法值返回 A0401（闭集，不能静默当成单选）。
     */
    @NotNull(message = "题型不能为空")
    private Integer type;

    /** 题干（必填，≤2000，纯文本） */
    @NotBlank(message = "题干不能为空")
    @Size(max = 2000, message = "题干不能超过 2000 个字符")
    private String stem;

    /** 选项列表（必填，2–6 个；多选至少 3 个） */
    @NotNull(message = "选项不能为空")
    @Size(min = 2, max = 6, message = "选项数量需在 2 ~ 6 个之间")
    private List<QuizOptionItem> options;

    /**
     * 正确选项标签（必填，升序无分隔，如 "B" / "ABD"）
     *
     * 不做格式注解："ca"、"C,A" 都会被
     * QuizLabelUtils.normalize 归一化，归一化后不合法才报错。
     * 在注解里写死正则会把「大小写/顺序不同」这种无害输入也拒掉。
     */
    @NotBlank(message = "正确答案不能为空")
    @Size(max = 8, message = "正确答案最多 6 个标签")
    private String correctLabels;

    /** 解析（选填，≤2000） */
    @Size(max = 2000, message = "解析不能超过 2000 个字符")
    private String analysis;

    /** 难度：1-易，2-中，3-难；不传按 1 处理 */
    private Integer difficulty;

    /** 题源（选填，≤128） */
    @Size(max = 128, message = "题源不能超过 128 个字符")
    private String source;

    /** 题序（数值越小越靠前）；不传按 0 处理 */
    private Integer questionSeq;
}

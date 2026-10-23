package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交作答请求（C 端）
 *
 * 三个字段都必填，且 batchId / questionId 都要带：
 * - batchId 是幂等键的一半（另一半是 questionId），
 * 缺了它就无法保证「一题一批只判一次」——断网重试会落两条记录；
 * - questionId 必须来自服务端返回的那一批，不能由前端自选
 * （否则可以挑一道简单题刷正确率）。Service 只校验它存在且启用，
 *
 * 为什么不校验「questionId 属于 batchId」：服务端不持久化批次内容
 * （题目不含答案，整批交给前端即可）。最坏影响是用户给自己刷出脏的作答记录，
 * 不涉及越权或他人数据——这个取舍写进了 Service 的类注释。
 *
 * chosenLabels 用字符串而不是数组：与库里的
 * correct_labels 同一形态（升序无分隔），判分时归一化后直接比较，
 * 不必做两边的集合转换。"AC" / "ca" / "C,A" 都会被归一化。
 */
@Data
public class QuizAnswerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 批次 ID（必填，由开批接口返回） */
    @NotNull(message = "批次 id 不能为空")
    @Positive(message = "批次 id 必须为正整数")
    private Long batchId;

    /** 题目 ID（必填） */
    @NotNull(message = "题目 id 不能为空")
    @Positive(message = "题目 id 必须为正整数")
    private Long questionId;

    /**
     * 所选标签（必填，如 "AC"；单字符亦可）
     *
     * 单选传 2 个及以上标签是非法请求（正常 UI 产生不了），返回 A0401；
     * 多选只传 1 个是用户的真实选择，正常判错、不拒绝。
     */
    @NotBlank(message = "请选择答案")
    @Size(max = 8, message = "答案最多 6 个标签")
    private String chosenLabels;
}

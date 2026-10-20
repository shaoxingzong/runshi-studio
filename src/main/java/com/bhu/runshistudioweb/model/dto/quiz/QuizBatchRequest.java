package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 开一批题目的请求（C 端）
 *
 * author: shaoshing
 *
 * <p><b>为什么没有 batchId</b>：批次 ID 由<b>服务端</b>生成后返回。
 * 让前端传批次 ID 等于把「本轮是哪几题」的解释权交出去——
 * 前端可以复用别人的批次号，或者伪造一个从未存在的批次。
 *
 * <p><b>为什么教材/章节是可空的</b>：
 * <ul>
 *     <li>顺序模式：两者都必填（必须明确刷哪一章）；</li>
 *     <li>错题重刷：都可空（空 = 全部错题），传了就是「只刷这一章的错题」；</li>
 *     <li>随机刷题：都可空（空 = 全库随机），传了就是「只抽这一范围」。</li>
 * </ul>
 * 这种「按模式决定必填性」的规则没法用注解表达，由 Service 校验，
 * 且提示要写清是哪个模式缺了哪个参数。
 */
@Data
public class QuizBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 模式：0-顺序刷题，1-错题重刷，2-随机刷题（必填）
     */
    @NotNull(message = "刷题模式不能为空")
    private Integer mode;

    /**
     * 教材名称（顺序模式必填；其余模式选填 = 范围过滤）
     */
    private String textbookName;

    /**
     * 章节名称（顺序模式必填；其余模式选填 = 范围过滤）
     */
    private String chapterName;

    /**
     * 本轮题量；不传用 {@code studio.quiz.default-size}，超过上限收敛到上限
     */
    private Integer size;

    /**
     * 顺序模式下是否「重新刷本章」
     *
     * <p>{@code false}（默认）：排除已作答（对或错）的题，所以刷完 20 题再点开始
     * 拿到的是接下来的题；全刷完才提示「本章已全部刷完」。
     * {@code true}：忽略排除条件，从头再来一遍——作答记录照常写，
     * 错题本不会因此重复入本（唯一键兜住）。
     */
    private Boolean restart;
}

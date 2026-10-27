package com.bhu.runshistudioweb.model.dto.quiz;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 开一批题目的请求（C 端）
 *
 * 没有 batchId：批次 ID 由服务端生成后返回。让前端传等于把「本轮是哪几题」的解释权交出去，
 * 前端可以复用别人的批次号或伪造一个从未存在的批次。
 *
 * 教材/章节的可空性随模式变化：顺序模式两者都必填；错题重刷、随机刷题都可空
 * （空 = 全部错题 / 全库随机），传了就是限定范围。这类规则注解表达不了，由 Service 校验。
 *
 * @author shaoshing
 */
@Data
public class QuizBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模式：0-顺序刷题，1-错题重刷，2-随机刷题（必填） */
    @NotNull(message = "刷题模式不能为空")
    private Integer mode;

    /** 教材名称（顺序模式必填；其余模式选填 = 范围过滤） */
    private String textbookName;

    /** 章节名称（顺序模式必填；其余模式选填 = 范围过滤） */
    private String chapterName;

    /** 本轮题量；不传用 studio.quiz.default-size，超过上限收敛到上限 */
    private Integer size;

    /**
     * 顺序模式下是否「重新刷本章」
     *
     * false（默认）：排除已作答（对或错）的题，刷完 20 题再点开始拿到的是接下来的题。
     * true：忽略排除条件从头再来——作答记录照常写，错题本不会重复入本（唯一键兜住）。
     */
    private Boolean restart;
}

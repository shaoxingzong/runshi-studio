package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 学习概览（C 端首页的四个数字）
 *
 * @author shaoshing
 */
@Data
public class QuizOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 累计作答（不含跳过，含重做） */
    private Integer answeredTotal;

    /** 累计答对 */
    private Integer correctTotal;

    /** 正确率（0~1）；累计作答为 0 时为 null，前端显示「--」 */
    private Double accuracy;

    /** 待复习错题数 */
    private Integer wrongPending;

    /** 已消解错题数（当前状态） */
    private Integer wrongResolved;
}

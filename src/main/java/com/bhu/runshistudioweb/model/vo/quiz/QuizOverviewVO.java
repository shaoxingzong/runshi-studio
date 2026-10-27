package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 学习概览（C 端首页的四个数字）
 *
 * 口径（与前端展示文案一一对应，改口径要同步改文案）：answeredTotal 是 result IN (1,2) 的条数，
 * 不含跳过、含重做；correctTotal 是 result = 1 的条数；accuracy = correctTotal / answeredTotal；
 * wrongPending 是错题本 status = 0，wrongResolved 是 status = 1（当前状态，不是历史累计）。
 *
 * accuracy 是 Double 且允许 null：0 次作答时正确率是「无定义」而不是 0%，返回 0 会让前端显示「0%」，
 * 读起来像「全错」——那是错误的信息，前端对 null 显示「--」。
 *
 * 正确率用「全部提交」而不是「首答」口径：首答口径不会随复习上升，用户刷完错题回来看纹丝不动，
 * 反馈是坏的；用全部提交时刷错题能让正确率回升，正是产品想要的激励方向。
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

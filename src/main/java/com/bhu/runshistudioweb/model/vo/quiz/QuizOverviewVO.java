package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;

/**
 * 学习概览（C 端首页的四个数字）
 *
 * author: shaoshing
 *
 * <p>四个指标的口径（<b>与前端展示文案一一对应，改口径要同步改文案</b>）：
 * <table border="1">
 *     <caption>口径</caption>
 *     <tr><th>字段</th><th>含义</th><th>口径细节</th></tr>
 *     <tr><td>{@code answeredTotal}</td><td>累计作答</td>
 *     <td>{@code result IN (1,2)} 的条数，<b>不含跳过</b>，含重做</td></tr>
 *     <tr><td>{@code correctTotal}</td><td>累计答对</td><td>{@code result = 1} 的条数</td></tr>
 *     <tr><td>{@code accuracy}</td><td>正确率</td>
 *     <td>{@code correctTotal / answeredTotal}；<b>分母为 0 时返回 null</b></td></tr>
 *     <tr><td>{@code wrongPending}</td><td>待复习错题</td><td>错题本 {@code status = 0}</td></tr>
 *     <tr><td>{@code wrongResolved}</td><td>已消解</td>
 *     <td>错题本 {@code status = 1}（<b>当前状态</b>，不是历史累计）</td></tr>
 * </table>
 *
 * <p><b>为什么 {@code accuracy} 是 {@code Double} 且允许 null</b>：
 * 0 次作答时正确率是「无定义」，不是 0%。返回 0 会让前端显示「0%」，
 * 读起来像「全错」——那是错误的信息。前端对 null 显示「--」。
 *
 * <p><b>为什么正确率用「全部提交」口径而不是「首答」</b>：
 * 首答口径需要额外标记「这是第几次作答」，且它<b>不会随复习上升</b>——
 * 用户刷完错题回来看，正确率纹丝不动，反馈是坏的。
 * 用全部提交口径时，刷错题能让正确率回升，正是产品想要的激励方向。
 */
@Data
public class QuizOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 累计作答（不含跳过，含重做）
     */
    private Integer answeredTotal;

    /**
     * 累计答对
     */
    private Integer correctTotal;

    /**
     * 正确率（0~1）；累计作答为 0 时为 {@code null}，前端显示「--」
     */
    private Double accuracy;

    /**
     * 待复习错题数
     */
    private Integer wrongPending;

    /**
     * 已消解错题数（当前状态）
     */
    private Integer wrongResolved;
}

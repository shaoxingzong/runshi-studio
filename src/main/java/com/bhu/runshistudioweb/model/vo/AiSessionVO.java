package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话视图对象（「我的会话」列表使用）
 *
 * author: shaoshing
 *
 * <p>只有四个字段：{@code id}（点进去看历史）、{@code title}（列表主文案）、
 * {@code updatedAt}（「最近聊过」的时间）、{@code messageCount}（聊了多少轮）。
 *
 * <p><b>刻意不包含</b>：{@code userId}（属于内部归属字段，返回它等于把账号关系暴露给前端，
 * 而列表本来就是「当前用户的会话」，没有任何前端场景需要它）、
 * {@code createdBy / updatedBy / createdAt / deletedAt}（审计字段）。
 * 与其它模块一致：脱敏靠「目标 VO 没有这些字段」，而不是拷完再手动置空。
 *
 * <p><b>两个字段的类型选择都是有理由的</b>：
 * <ul>
 *     <li>{@code id} 用 {@code Long} + {@code ToStringSerializer}：雪花 ID 19 位，
 *     超出 JS 安全整数范围，必须出字符串；</li>
 *     <li>{@code messageCount} 用 {@code Integer}：计数天然不超过 int 范围，
 *     而且前端要拿它做展示（「共 12 条」），
 *     <b>不能</b>用 Long——会被全局的 Long → 字符串约定变成 {@code "12"}。
 *     一句话：<b>主键出字符串，计数出数字</b>（与 StatisticOverviewVO 同一口径）。</li>
 * </ul>
 */
@Data
public class AiSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（雪花算法 19 位，序列化为字符串）
     * <p>前端点进某条会话时，用它调历史 / 提问 / 删除接口
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 会话标题（取首问前 30 字）
     */
    private String title;

    /**
     * 最后活跃时间（每次追加消息都会刷新）
     * <p>由全局 JsonConfig 统一序列化为 {@code yyyy-MM-dd HH:mm:ss}
     */
    private LocalDateTime updatedAt;

    /**
     * 会话下的消息条数（<b>批量统计</b>得出，不是逐条 count）
     */
    private Integer messageCount;
}

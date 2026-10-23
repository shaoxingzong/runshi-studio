package com.bhu.runshistudioweb.model.vo.traffic;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

// 流量统计总览视图对象（管理端「流量分析」页）
//
// 计数字段一律用 Integer 而不是 Long：与
// com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO 同一条理由——项目的全局约定把 Long 序列化成字符串
// （为 19 位雪花 ID 让路），而计数要的是数字。用 Long 存计数会让前端拿到
// "128"，排序与加法还会按字典序算。
//
// UV 口径必须知道：按 visitor_id（前端 localStorage 随机 uuid）去重，
// 因此「同一人换设备 / 清缓存 / 用无痕模式」会算成多个 UV。
// 这是轻量埋点的固有边界——要更准就得记登录用户 ID 或设备指纹，代价是隐私与复杂度，
// 与本期「不记 IP / UA」的取舍一致。页面文案需注明这个口径。
@Data
public class TrafficOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 统计区间内的总 PV（每次访问记一条）
    private Integer totalPv;

    // 统计区间内的总 UV（按 visitor_id 去重）
    private Integer totalUv;

    // 今日 PV（自然日，按服务器时区）
    private Integer todayPv;

    // 今日 UV
    private Integer todayUv;

    // 每日趋势（按日期倒序，近 N 天；无访问的日期补 0，避免图表断档）
    private List<DailyTrafficVO> daily;

    // 热门页面 TOP N（按 PV 倒序）
    private List<PageTrafficVO> topPages;
}

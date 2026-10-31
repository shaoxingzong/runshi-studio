package com.bhu.runshistudioweb.model.vo.traffic;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 流量统计总览视图对象（管理端「流量分析」页）
 *
 * @author shaoshing
 */
@Data
public class TrafficOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计区间内的总 PV（每次访问记一条） */
    private Integer totalPv;

    /** 统计区间内的总 UV（按 visitor_id 去重） */
    private Integer totalUv;

    /** 今日 PV（自然日，按服务器时区） */
    private Integer todayPv;

    /** 今日 UV */
    private Integer todayUv;

    /** 每日趋势（按日期倒序，近 N 天；无访问的日期补 0，避免图表断档） */
    private List<DailyTrafficVO> daily;

    /** 热门页面 TOP N（按 PV 倒序） */
    private List<PageTrafficVO> topPages;
}

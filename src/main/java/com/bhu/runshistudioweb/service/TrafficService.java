package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;

/**
 * 流量统计服务
 *
 * @author shaoshing
 */
public interface TrafficService extends IService<StudioPageVisit> {

    /**
     * 记录一次页面访问
     *
     * @param request 埋点上报，不能为 null；pagePath 与 visitorId 必填；referrerHost 与 deviceType 选填
     * @throws BusinessException 请求体为 null 时抛 A0401。本方法没有返回值，成功即「已记录」
     */
    void report(TrafficReportRequest request);

    /**
     * 流量总览：PV/UV 总量 + 今日 + 每日趋势 + 热门页面
     *
     * @param days 趋势天数（1-90）。传 0 或负数按 30 处理；超过 90 收敛到 90，不会报错
     * @return 总览数据，永不为 null；没有访问记录时各计数为 0、daily 仍返回完整的 N 天（值为 0）、topPages 为空列表（不是 null）
     */
    TrafficOverviewVO getOverview(int days);
}

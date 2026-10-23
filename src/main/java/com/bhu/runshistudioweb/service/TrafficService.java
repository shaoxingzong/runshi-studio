package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;

// 流量统计服务
//
// 两条口径：
// - 埋点（ #report）匿名可调、只增不改、失败不影响用户体验——
// 它挂在门户每个页面上，一旦报错弹窗会比没有统计更糟；
// - 统计（ #getOverview）只给管理端，聚合查询。
public interface TrafficService extends IService<StudioPageVisit> {

    // 记录一次页面访问
    //
    // 用户 ID 从登录态取（未登录为 NULL），不信请求体——
    // 否则任何人都能伪造他人的访问记录。
    //
    // @param request 埋点上报（pagePath 与 visitorId 必填）
    void report(TrafficReportRequest request);

    // 流量总览：PV/UV 总量 + 今日 + 每日趋势 + 热门页面
    //
    // @param days 趋势天数（1-90，超出收敛到边界；传 0 或负数按 30 处理）
    // @return 总览数据
    TrafficOverviewVO getOverview(int days);
}

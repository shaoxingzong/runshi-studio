package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;

/**
 * 流量统计服务
 *
 * 两个方法的准入、幂等与失败处理差别很大，不能套用同一套预期：
 * 埋点（report）匿名可调、只增不改、失败不影响用户体验——它挂在门户每个页面上，
 * 一旦报错弹窗会比没有统计更糟；统计（getOverview）只给管理端，是聚合查询。
 *
 * 匿名也要统计，因为官网的主要读者就是游客。但也因此，埋点接口是「任何人都能往里写数据」
 * 的入口，它必须自己承担防脏数据与防泄露的责任：不收 userId、不收完整 URL、
 * 路径在服务端再规范化。
 *
 * @author shaoshing
 */
public interface TrafficService extends IService<StudioPageVisit> {

    /**
     * 记录一次页面访问
     *
     * 埋点是旁路能力，它的价值远低于「用户能正常浏览」，因此取舍一律偏向「不要出错」。
     * 路径在服务端再规范化一次（前端可能被旧缓存，也可能有人直接调接口），
     * 超长与空值都有兜底。用户身份只从登录态取：一旦采信请求体，
     * 任何人都能伪造他人的访问记录，而且伪造出来的数据看起来完全正常。
     *
     * 坑：本方法不幂等，每次调用都新增一条记录，前端重复上报（路由抖动、组件重复挂载）
     * 会真的产生多条 PV。这是刻意的：PV 的定义就是「访问次数」，去重只能靠前端节流。
     *
     * UV 精度边界：按 visitorId（前端 localStorage 里的随机 uuid）去重，
     * 因此「同一人换设备 / 清缓存 / 用无痕模式」会算成多个 UV。这是轻量埋点的固有代价。
     *
     * @param request 埋点上报，不能为 null；pagePath 与 visitorId 必填；
     *                referrerHost 与 deviceType 选填
     * @throws BusinessException 请求体为 null 时抛 A0401。本方法没有返回值，成功即「已记录」
     */
    void report(TrafficReportRequest request);

    /**
     * 流量总览：PV/UV 总量 + 今日 + 每日趋势 + 热门页面
     *
     * 一次返回四组数据，而不是让前端分四次请求再自己拼。四组数据的时间区间不同
     * （总量是近 N 天、今日是自然日），这个差异必须由服务端统一处理——
     * 交给前端算会让「今天是哪天」取决于浏览器时区，而服务器用的是自己的时区。
     *
     * 坑：趋势必须补 0。直接按天分组只返回有数据的日期，前端图表会把「没人来」和「没数据」
     * 画成同一回事，而这两者的运营含义完全相反。另一个坑是区间 UV 不是「每日 UV 之和」：
     * 同一个人连续三天来访，区间 UV 算 1 而每日 UV 之和是 3。
     *
     * 本方法只读、无副作用；不做角色校验，管理端权限由 Controller 上的 {@code @SaCheckRole} 负责。
     *
     * @param days 趋势天数（1-90）。传 0 或负数按 30 处理；超过 90 收敛到 90，不会报错
     * @return 总览数据，永不为 null；没有访问记录时各计数为 0、
     *         daily 仍返回完整的 N 天（值为 0）、topPages 为空列表（不是 null）
     */
    TrafficOverviewVO getOverview(int days);
}

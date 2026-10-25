package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.model.entity.StudioPageVisit;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;

/**
 * 流量统计服务
 *
 * <p>本接口是流量模块对调用方的契约，实现类只解释「为什么这么实现」。
 * 两个方法的准入、幂等与失败处理差别很大，不能套用同一套预期。
 *
 * <p>两条口径：
 * <ul>
 *     <li>埋点（ report）匿名可调、只增不改、失败不影响用户体验——
 *     它挂在门户每个页面上，一旦报错弹窗会比没有统计更糟；</li>
 *     <li>统计（ getOverview）只给管理端，聚合查询。</li>
 * </ul>
 *
 * <p>为什么匿名也要统计：官网的主要读者就是游客，只统计登录用户等于只看到冰山一角。
 * 但也因此，埋点接口是「任何人都能往里写数据」的入口，
 * 它必须自己承担防脏数据与防泄露的责任（不收 userId、不收完整 URL、路径在服务端再规范化）。
 *
 * @author shaoshing
 */
public interface TrafficService extends IService<StudioPageVisit> {

    /**
     * 记录一次页面访问
     *
     * <p>为什么这样设计：埋点是旁路能力，它的价值远低于「用户能正常浏览」。
     * 因此本方法的取舍一律偏向「不要出错」：路径在服务端再规范化一次
     * （前端可能被旧缓存，也可能有人直接调接口），超长与空值都有兜底，
     * 用户身份只从登录态取。
     *
     * <p>为什么用户 ID 不信请求体：一旦采信，任何人都能伪造他人的访问记录，
     * 而且伪造出来的数据看起来完全正常，事后无法分辨。
     * 只从登录态取之后，「未登录 = NULL」就成了一个可靠的语义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体。{@code @Valid} 只校验字段级约束，请求体整体为 null 时不会触发；</li>
     *     <li>② 路径规范化：剥掉 query 与 hash。query 里可能带 token（分享链接、营销参数），
     *     把它原样存进一张匿名可写的表等于把凭证写进日志；</li>
     *     <li>③ 填访客标识、来源域名与设备类型，空值统一存 NULL（全局约定「空值存 NULL」）；</li>
     *     <li>④ 用户身份从登录态取，未登录存 NULL；</li>
     *     <li>⑤ 落库。这一步只有插入，没有更新——埋点表只增不改。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>不是幂等</b>的——每次调用都会新增一条记录，
     * 前端重复上报（路由抖动、组件重复挂载）会真的产生多条 PV。
     * 这是刻意的：PV 的定义就是「访问次数」，服务端无法判断两次上报是
     * 「用户刷新了一次」还是「代码抖了一下」，去重只能靠前端节流。
     * 另一个坑是 visitorId 必填：没有它这条记录既不能算 PV 也不能算 UV，
     * 收下来只会污染统计。
     *
     * <p>UV 的精度边界（调用方与产品都需要知道）：UV 按 visitorId
     * （前端 localStorage 里的随机 uuid）去重，因此「同一人换设备 / 清缓存 / 用无痕模式」
     * 会算成多个 UV。这是轻量埋点的固有代价——要更准就得记登录用户 ID 或设备指纹，
     * 代价是隐私与复杂度，与「不记 IP / UA」的取舍一致。页面文案需注明这个口径。
     *
     * @param request 埋点上报，不能为 null；pagePath 与 visitorId 必填
     *                （pagePath 必须以 / 开头且 ≤256，visitorId ≤64）；
     *                referrerHost 与 deviceType 选填。
     *                字段级约束由 Controller 的 {@code @Valid} 先拦一道，
     *                本方法只兜「整体为 null」这一种
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null 时抛 A0401。
     *         注意：本方法<b>没有返回值</b>，成功即「已记录」；
     *         调用方（Controller）固定回一个 true，不代表服务端做了额外确认
     */
    void report(TrafficReportRequest request);

    /**
     * 流量总览：PV/UV 总量 + 今日 + 每日趋势 + 热门页面
     *
     * <p>为什么这样设计：管理端要的是「近期态势」，因此一次返回四组数据，
     * 而不是让前端分四次请求再自己拼。四组数据的时间区间不同
     * （总量是近 N 天、今日是自然日），这个差异必须由服务端统一处理——
     * 交给前端算会让「今天是哪天」取决于浏览器时区，而服务器用的是自己的时区。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 收敛天数：非正数按默认值，超上限压到上限，不报错；</li>
     *     <li>② 推出统计区间：从「今天往前 N-1 天」的零点开始，到今天结束，
     *     这样「近 N 天」包含今天、且恰好是 N 个自然日；</li>
     *     <li>③ 依次取总量 PV、总量 UV、今日 PV、今日 UV；</li>
     *     <li>④ 按天聚合趋势，并<b>补齐没有数据的日期</b>；</li>
     *     <li>⑤ 取热门页面 TOP N。</li>
     * </ol>
     *
     * <p>容易踩的坑：趋势必须补 0。直接按天分组只会返回有数据的日期，
     * 前端图表会把「没人来」和「没数据」画成同一回事（断档），
     * 而这两者的运营含义完全相反——前者要反思内容，后者要反思埋点。
     * 另一个坑是 UV 的去重口径：区间 UV 不是「每日 UV 之和」，
     * 同一个人连续三天来访问，区间 UV 算 1 而每日 UV 之和是 3。
     *
     * <p>本方法只读、无副作用，重复调用幂等（期间数据不变时结果一致）。
     * 它<b>不做</b>角色校验，管理端权限由 Controller 上的 {@code @SaCheckRole} 负责。
     *
     * @param days 趋势天数（1-90）。传 0 或负数按 30 处理；超过 90 收敛到 90，
     *             不会报错——前端传错参数不该让整个页面查不出来
     * @return 总览数据，永不为 null；没有访问记录时各计数为 0、
     *         daily 仍返回完整的 N 天（值为 0）、topPages 为空列表（不是 null）
     */
    TrafficOverviewVO getOverview(int days);
}

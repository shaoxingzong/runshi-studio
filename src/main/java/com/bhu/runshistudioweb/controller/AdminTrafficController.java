package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.model.vo.traffic.TrafficOverviewVO;
import com.bhu.runshistudioweb.service.TrafficService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckRole;

/**
 * 流量统计管理端接口（只读）
 *
 * 为什么只读：流量数据由门户页面自己上报（见 TrafficController 的 /traffic/report），
 * 管理端只负责看。给它开写接口没有产品意义——没有任何人需要手工往访问日志里插一条记录，
 * 而一旦有了写入口，统计数据的可信度就没了。
 *
 * 为什么挂在 /admin 前缀：与其它管理端接口同一惯例，
 * 与匿名埋点接口（/traffic/report）在路径上分开，
 * 避免将来有人为了省事把白名单写成 /traffic/** 把统计一起放出去。
 * 两条路径的差别只有前缀，但白名单是精确匹配，所以这个差别足够把权限分开。
 *
 * 为什么必须限管理员：这里能看到全站访问量、热门页面与每日趋势，
 * 属于运营数据。游客能看到「首页有多少张证书」，但不该看到「每天有多少人访问、
 * 他们都在看哪一页」——后者是竞争情报。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/traffic")
@Tag(name = "流量统计", description = "管理员查看门户 PV/UV、每日趋势与热门页面")
@RequiredArgsConstructor
public class AdminTrafficController {

    private final TrafficService trafficService;

    /**
     * 流量总览
     *
     * <p>为什么这样设计：一个接口返回「总量 + 今日 + 每日趋势 + 热门页面」四块数据，
     * 而不是拆成四个接口。它们来自同一次时间范围，拆开会让四个请求各自算一遍边界，
     * 跨零点的请求之间还会出现「今日 PV 比总量还大」这类自相矛盾的数字。
     * 管理页一次拉完，也好过一次渲染触发四次请求。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收天数参数。用基本类型 int 并给了默认值 30：缺参时 Spring 填默认值，
     *     不会抛「缺少必填参数」，因此这里不需要包装类型 Integer；</li>
     *     <li>② 交给 Service。注意 Service 里第一步是「收敛天数」再算时间边界——
     *     顺序反了的话，days 传 0 会算出「明天开始」的区间，查询结果全是 0 且不报错；</li>
     *     <li>③ 包成统一响应体。VO 里的计数字段用 Integer 而不是 Long，
     *     因为项目的全局约定把 Long 序列化成字符串（为 19 位雪花 ID 让路），
     *     而计数需要的是数字。</li>
     * </ol>
     *
     * <p>容易踩的坑：days 传 0 或负数不会报错，Service 会按 30 天处理；传 365 会被收敛到 90。
     * 这是刻意的——趋势图只是「看近期态势」，传一个超大值除了拖慢查询没有任何收益，
     * 而报错会让「手滑输了个 0」变成一次失败的页面加载。
     *
     * @param days 趋势天数：不传时为 30；小于 1 按 30 处理，大于 90 收敛到 90
     * @return PV/UV 总量、今日值、每日趋势（无访问的日期补 0）、热门页面 TOP10；
     * 没有任何访问记录时各项计数为 0、列表为空，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
     */
    @GetMapping("/overview")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】流量总览", description = "PV/UV 总量 + 今日 + 每日趋势 + 热门页面 TOP10")
    public BaseResponse<TrafficOverviewVO> overview(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        // ①②③ 天数收敛、时间边界与补零都在 Service 里完成，Controller 只负责传参与包装
        return ResultUtils.success(trafficService.getOverview(days));
    }
}

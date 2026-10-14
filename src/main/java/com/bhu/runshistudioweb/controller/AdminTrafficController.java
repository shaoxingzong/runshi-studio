package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.model.vo.TrafficOverviewVO;
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
 * author: shaoshing
 *
 * <p><b>为什么挂在 {@code /admin} 前缀</b>：与其它管理端接口同一惯例，
 * 与匿名埋点接口（{@code /traffic/report}）在路径上分开，
 * 避免将来有人为了省事把白名单写成 {@code /traffic/**} 把统计一起放出去。
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
     * @param days 趋势天数（选填，默认 30，上限 90；非法值由 Service 收敛）
     * @return PV/UV 总量、今日值、每日趋势、热门页面 TOP10
     */
    @GetMapping("/overview")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】流量总览", description = "PV/UV 总量 + 今日 + 每日趋势 + 热门页面 TOP10")
    public BaseResponse<TrafficOverviewVO> overview(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResultUtils.success(trafficService.getOverview(days));
    }
}

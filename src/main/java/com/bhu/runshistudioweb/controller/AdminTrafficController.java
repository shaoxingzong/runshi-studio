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
     * @param days 趋势天数，缺省 30；小于 1 按 30 处理，大于 90 收敛到 90
     * @return PV/UV 总量、今日值、每日趋势与热门页面 TOP10；无数据时计数为 0、列表为空
     */
    @GetMapping("/overview")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】流量总览", description = "PV/UV 总量 + 今日 + 每日趋势 + 热门页面 TOP10")
    public BaseResponse<TrafficOverviewVO> overview(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        // ②③ 天数收敛、时间边界与补零都在 Service 里完成，Controller 只负责传参与包装
        return ResultUtils.success(trafficService.getOverview(days));
    }
}

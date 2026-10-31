package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.vo.statistic.StatisticOverviewVO;
import com.bhu.runshistudioweb.service.StatisticService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：官网首页「大盘数字」
 *
 * @author shaoshing
 */
@Tag(name = "统计模块", description = "官网首页大盘数据（匿名可访问）")
@RestController
@RequestMapping("/statistic")
@RequiredArgsConstructor
public class StatisticController {

    private final StatisticService statisticService;

    /**
     * 官网首页大盘：证书总数与级别、类型分布
     *
     * @return 证书总数、按级别与类型分布（空维度补 0），空库时 key 集合仍完整
     */
    @GetMapping("/overview")
    @Operation(summary = "官网首页大盘", description = "匿名可访问；证书总数与级别、类型分布，空维度补 0")
    public BaseResponse<StatisticOverviewVO> getOverview() {
        return ResultUtils.success(statisticService.getOverview());
    }
}

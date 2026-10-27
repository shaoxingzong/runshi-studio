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
 * 匿名可访问：路径以精确形式 /statistic/overview 登记在白名单（SaTokenMvcConfig），
 * 不能写成 /statistic/**，否则将来新增的管理端统计接口会被一起放行。
 *
 * 本类不加鉴权注解是刻意的：白名单 + 默认登录拦截下，不加注解即「匿名可访问」。
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
     * 刻意不含成员人数：本接口匿名可访问，人数属于成员信息，与「团队成员不对外展示」冲突。
     * 计数用 Integer 而非 Long——全局把 Long 序列化成字符串，前端图表需要的是数字。
     *
     * @return 证书总数、按级别与类型分布（空维度补 0），空库时 key 集合仍完整
     */
    @GetMapping("/overview")
    @Operation(summary = "官网首页大盘", description = "匿名可访问；证书总数与级别、类型分布，空维度补 0")
    public BaseResponse<StatisticOverviewVO> getOverview() {
        // ① 无入参：本期不做任何筛选，也不接受年份之类的条件
        // ②③ 口径（排除逻辑删除、脏值忽略、空维度补 0）全在 Service 里，Controller 不做加工
        return ResultUtils.success(statisticService.getOverview());
    }
}

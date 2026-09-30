package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.vo.StatisticOverviewVO;
import com.bhu.runshistudioweb.service.StatisticService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：官网首页「大盘数字」
 *
 * author: shaoshing
 *
 * <p><b>匿名可访问</b>：这是首页的数据，游客必须能拿到，已登记在 Sa-Token 白名单的
 * 精确路径 {@code /statistic/overview} 上（见 SaTokenMvcConfig）。
 * 注意白名单必须写精确路径，禁止写 {@code /statistic/**}——
 * 将来若新增管理端统计接口（例如含敏感数据），会被这条通配一起放行。
 *
 * <p>本 Controller 不加任何鉴权注解：白名单 + 默认登录拦截的组合下，
 * 不加注解意味着「白名单内匿名可访问」，这是刻意设计，不是漏加。
 *
 * <p>无入参：本期不做任何筛选（不按年份、不按届别）。
 * 将来要加参数时应当在 Service 方法上扩展，并保持「空库返回完整 key 集合」的口径。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/statistic/overview}。
 */
@Tag(name = "统计模块", description = "官网首页大盘数据（匿名可访问）")
@RestController
@RequestMapping("/statistic")
public class StatisticController {

    @Resource
    private StatisticService statisticService;

    /**
     * 官网首页大盘：团队人数、证书数量，以及证书的级别 / 类型分布（供图表渲染）
     *
     * @return 大盘统计；计数字段是 JSON 数字（VO 用 Integer，避开全局的 Long → 字符串规则）
     */
    @GetMapping("/overview")
    @Operation(summary = "官网首页大盘", description = "匿名可访问；成员/证书总数与级别、类型分布，空维度补 0")
    public BaseResponse<StatisticOverviewVO> getOverview() {
        return ResultUtils.success(statisticService.getOverview());
    }
}

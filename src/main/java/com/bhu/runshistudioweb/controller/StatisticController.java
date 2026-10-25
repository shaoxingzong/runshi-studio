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
 * 匿名可访问：这是首页的数据，游客必须能拿到，已登记在 Sa-Token 白名单的
 * 精确路径 /statistic/overview 上（见 SaTokenMvcConfig）。
 * 注意白名单必须写精确路径，禁止写 /statistic/**——
 * 将来若新增管理端统计接口（例如含敏感数据），会被这条通配一起放行。
 *
 * 本 Controller 不加任何鉴权注解：白名单 + 默认登录拦截的组合下，
 * 不加注解意味着「白名单内匿名可访问」，这是刻意设计，不是漏加。
 * 反过来说，如果哪天这个路径从白名单里被删掉，游客会立刻拿到 A0201，
 * 而代码里看不出任何异常——白名单与 Controller 是两处必须同步的地方。
 *
 * 无入参：不做任何筛选（不按年份、不按届别）。
 * 将来要加参数时应当在 Service 方法上扩展，并保持「空库返回完整 key 集合」的口径，
 * 因为前端图表按固定维度渲染，key 缺失会导致维度错乱或渲染报错。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/statistic/overview。
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
     * 官网首页大盘：证书数量，以及证书的级别 / 类型分布（供图表渲染）
     *
     * <p>为什么这样设计：刻意不含成员人数（总数 / 在读 / 已毕业）。
     * 本接口匿名可访问，对外暴露团队人数同样属于展示成员信息，
     * 与「团队成员不对外展示」的产品规则冲突。首页大盘因此只有证书维度；
     * 管理端若需要人数，应当另开一个要求 admin 的统计接口，而不是在这里加字段。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参，也不接受任何筛选条件；</li>
     *     <li>② 调 Service 取大盘数据。实现侧保证三条口径：排除逻辑删除的数据、
     *     枚举外的脏值忽略而不报错、空维度补 0 并返回完整的 key 集合；</li>
     *     <li>③ 包成统一响应体。空库时各项计数为 0、分布 map 的 key 齐全，绝不返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：计数字段是 Integer 而不是 Long，这不是随手选的。
     * 项目的全局约定把 Long 序列化成字符串（为 19 位雪花 ID 让路），
     * 而前端图表需要的是数字——用 Long 存计数，前端拿到的是 "27"，
     * 排序与加法还会按字典序算。一句话记住：主键用 Long（出字符串），计数用 Integer（出数字）。
     *
     * @return 大盘统计：证书总数、按级别分布、按类型分布（缺位补 0）；
     * 空库时返回全 0 且 key 集合完整，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 本接口不校验登录，
     * 正常情况下不抛业务异常；数据库不可用时由全局异常处理器兜成 B0001
     */
    @GetMapping("/overview")
    @Operation(summary = "官网首页大盘", description = "匿名可访问；证书总数与级别、类型分布，空维度补 0")
    public BaseResponse<StatisticOverviewVO> getOverview() {
        // ① 无入参：本期不做任何筛选，也不接受年份之类的条件
        // ②③ 口径（排除逻辑删除、脏值忽略、空维度补 0）全在 Service 里，Controller 不做加工
        return ResultUtils.success(statisticService.getOverview());
    }
}

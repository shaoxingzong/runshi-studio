package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.config.TrafficRateLimitProperties;
import com.bhu.runshistudioweb.constant.TrafficRateLimitConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.manager.IpRateLimitManager;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.service.TrafficService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流量埋点接口（匿名可访问）
 *
 * 官网读者主要是游客，只统计登录用户等于只看到冰山一角。白名单只精确登记 /traffic/report，
 * 不能写成 /traffic/**——管理端挂在 /admin/traffic 下另有鉴权。
 *
 * 写入不记 IP、UA 与 query：query 里可能带 token，存进匿名可写的表等于把凭证写进日志。
 *
 * <b>必须限流</b>：这是全站唯一「匿名且直接写库」的接口。没有限流时，一个脚本循环调用
 * 就能在几分钟内把 studio_page_visit 写成几百万行——统计失真只是表象，真正的代价是
 * 管理端统计查询变慢、磁盘与备份成本上升，而且这些动作连注册都不需要。
 *
 * 限流检查放在调用 Service **之前**：本接口的成本几乎全在写库，放到写库之后再拦，
 * 数据库早已经被压过一轮了。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/traffic")
@Tag(name = "流量埋点", description = "门户页面访问上报（匿名可访问）")
@RequiredArgsConstructor
public class TrafficController {

    private final TrafficService trafficService;

    /** 解析真实客户端 IP：直接取 remoteAddr 时，Nginx 后面所有访客都是同一个 IP，限流形同虚设 */
    private final ClientIpManager clientIpManager;

    /** IP 级双窗口限流（与登录 / 注册 / 游客提问共用同一套实现） */
    private final IpRateLimitManager ipRateLimitManager;

    /** 埋点限流的阈值与总开关 */
    private final TrafficRateLimitProperties trafficRateLimitProperties;

    /**
     * 上报一次页面访问
     *
     * 用户身份由后端从登录态取，请求体里没有 userId，否则等于开放伪造他人访问记录。
     * 路径只收不含 query 的部分，且 Service 会再规范化一次——前端缓存里的旧代码可能还带着。
     *
     * @param request      pagePath 与 visitorId 必填，pagePath 须以 / 开头且不超过 256 字符
     * @param httpRequest  用于取限流所需的来源 IP 与转发头，由容器注入
     * @return 固定 true（表示已接收；失败会抛异常）
     */
    @PostMapping("/report")
    @Operation(summary = "页面访问上报", description = "匿名可访问；只记路径/访客/来源域名/设备，不记 IP 与 query")
    public BaseResponse<Boolean> report(@RequestBody @Valid TrafficReportRequest request,
                                        HttpServletRequest httpRequest) {
        // ① @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ② 限流先于业务：超限抛 A0501，前端埋点是旁路，收到限流也不会影响页面功能
        assertRateAllowed(httpRequest);
        // ③ Service 内会再规范化一次路径：前端缓存里的旧代码可能还带着 query
        trafficService.report(request);
        // ④ 固定 true：埋点是旁路，前端不需要根据结果做任何判断
        return ResultUtils.success(true);
    }

    /**
     * 按 IP 做双窗口限流检查
     *
     * IP 必须经 ClientIpManager 解析：只有 TCP 对端是可信代理时才采信 X-Real-IP /
     * X-Forwarded-For，否则攻击者伪造一个头就绕过了限流。
     *
     * @param httpRequest 当前请求，用于取 remoteAddr 与两个转发头
     */
    private void assertRateAllowed(HttpServletRequest httpRequest) {
        if (Boolean.FALSE.equals(trafficRateLimitProperties.getEnabled())) {
            return;
        }
        String ip = clientIpManager.resolveClientIp(
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("X-Real-IP"),
                httpRequest.getHeader("X-Forwarded-For"));
        ipRateLimitManager.assertAllowed(ip, TrafficRateLimitConstant.REPORT_KEY_PREFIX,
                trafficRateLimitProperties.getMinuteLimit(),
                trafficRateLimitProperties.getDailyLimit(),
                TrafficRateLimitConstant.REPORT_ACTION);
    }
}

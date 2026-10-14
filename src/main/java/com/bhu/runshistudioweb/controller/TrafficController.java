package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.service.TrafficService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流量埋点接口（<b>匿名可访问</b>）
 *
 * author: shaoshing
 *
 * <p><b>为什么匿名也要统计</b>：官网的主要读者就是游客，
 * 只统计登录用户等于只看到冰山一角——那是为了好看而不是为了有用。
 *
 * <p><b>为什么必须精确登记白名单</b>：只放行 {@code /traffic/report} 这一条，
 * 绝不能写成 {@code /traffic/**}——统计接口（{@code /admin/traffic/*}）另有鉴权，
 * 通配会把将来的内部接口一起放出去。
 *
 * <p>本接口<b>不返回数据</b>：埋点是旁路，前端也不需要根据结果做什么。
 * 失败由全局异常处理器兜成错误码，前端静默（不影响页面使用）。
 */
@RestController
@RequestMapping("/traffic")
@Tag(name = "流量埋点", description = "门户页面访问上报（匿名可访问）")
@RequiredArgsConstructor
public class TrafficController {

    private final TrafficService trafficService;

    /**
     * 上报一次页面访问
     *
     * <p>用户身份由后端从登录态取（未登录为 NULL），<b>不收</b>请求体里的 userId；
     * 路径只收不含 query 的部分（query 可能带 token）。
     *
     * @param request 埋点数据（pagePath 与 visitorId 必填）
     */
    @PostMapping("/report")
    @Operation(summary = "页面访问上报", description = "匿名可访问；只记路径/访客/来源域名/设备，不记 IP 与 query")
    public BaseResponse<Boolean> report(@RequestBody @Valid TrafficReportRequest request) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        trafficService.report(request);
        return ResultUtils.success(true);
    }
}

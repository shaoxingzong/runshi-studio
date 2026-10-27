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
 * 流量埋点接口（匿名可访问）
 *
 * 官网读者主要是游客，只统计登录用户等于只看到冰山一角。白名单只精确登记 /traffic/report，
 * 不能写成 /traffic/**——管理端挂在 /admin/traffic 下另有鉴权。
 *
 * 写入不记 IP、UA 与 query：query 里可能带 token，存进匿名可写的表等于把凭证写进日志。
 *
 * @author shaoshing
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
     * 用户身份由后端从登录态取，请求体里没有 userId，否则等于开放伪造他人访问记录。
     * 路径只收不含 query 的部分，且 Service 会再规范化一次——前端缓存里的旧代码可能还带着。
     *
     * @param request pagePath 与 visitorId 必填，pagePath 须以 / 开头且不超过 256 字符
     * @return 固定 true（表示已接收；失败会抛异常）
     */
    @PostMapping("/report")
    @Operation(summary = "页面访问上报", description = "匿名可访问；只记路径/访客/来源域名/设备，不记 IP 与 query")
    public BaseResponse<Boolean> report(@RequestBody @Valid TrafficReportRequest request) {
        // ① @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ② Service 内会再规范化一次路径：前端缓存里的旧代码可能还带着 query
        trafficService.report(request);
        // ③ 固定 true：埋点是旁路，前端不需要根据结果做任何判断
        return ResultUtils.success(true);
    }
}

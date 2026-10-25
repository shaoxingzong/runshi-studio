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
 * 为什么匿名也要统计：官网的主要读者就是游客，
 * 只统计登录用户等于只看到冰山一角——那是为了好看而不是为了有用。
 *
 * 为什么必须精确登记白名单：只放行 /traffic/report 这一条，
 * 绝不能写成 /traffic/**——统计接口（/admin/traffic/*）另有鉴权，
 * 通配会把将来的内部接口一起放出去。
 * 注意管理端挂在 /admin/traffic 下，与本类的 /traffic 是两个不同的前缀，
 * 白名单只登记后者，前者自然被「默认要求登录 + 角色校验」挡住。
 *
 * 本接口不返回数据（只返回一个 true 表示收到）：埋点是旁路，
 * 前端不需要根据结果做什么。失败由全局异常处理器兜成错误码，前端静默忽略即可——
 * 埋点报错绝不能弹窗打断用户，否则统计系统就成了体验的负担。
 *
 * 写入口径的隐私边界：只记路径、访客标识、来源域名与设备类型，
 * 不记 IP、不记 User-Agent、不记 query。query 里可能带 token（例如分享链接），
 * 把它原样存进一张匿名可写的表，等于把凭证写进了日志。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/traffic/report。
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
     * <p>为什么这样设计：用户身份由后端从登录态取（未登录就是空），
     * 请求体里没有 userId——前端传什么就信什么，等于开一个「伪造他人访问记录」的口子。
     * 路径只收不含 query 的部分：query 里可能带 token 或营销参数，
     * 存进埋点表就是把凭证写进了一张匿名可写的表。前端提交前应当剥掉 query 与 hash，
     * 但后端仍会再做一次规范化——前端代码可能还是旧缓存。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（路径非空且以 / 开头、
     *     访客标识非空），请求体整体为 null 时它不触发；</li>
     *     <li>② 交给 Service：规范化路径 → 取登录态里的用户 ID（未登录为空）→ 落库；</li>
     *     <li>③ 固定返回 true。本接口没有业务返回值，前端也不该据此做任何界面判断。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有鉴权注解，这是刻意设计——路径在白名单里，
     * 匿名可写。因此「写」这件事本身必须极便宜且极安全：字段全部有长度上限、
     * 路径必须以 / 开头、不记任何可识别个人的信息。若哪天给它加一个「备注」之类的
     * 自由文本字段，这个接口立刻会变成垃圾内容的入口。
     *
     * @param request 埋点数据：pagePath 与 visitorId 必填；referrerHost 与 deviceType 选填。
     *                pagePath 必须以 / 开头且不超过 256 字符
     * @return 固定为 true（表示已接收；失败会抛异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数不合法（A0401）时抛出；
     * 本接口不校验登录，因此不会抛 A0201
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

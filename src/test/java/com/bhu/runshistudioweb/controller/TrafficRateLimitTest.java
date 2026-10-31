package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.config.TrafficRateLimitProperties;
import com.bhu.runshistudioweb.constant.TrafficRateLimitConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.manager.ClientIpManager;
import com.bhu.runshistudioweb.manager.IpRateLimitManager;
import com.bhu.runshistudioweb.model.dto.traffic.TrafficReportRequest;
import com.bhu.runshistudioweb.service.TrafficService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 埋点上报限流的行为测试
 *
 * 单独覆盖 /traffic/report 的限流，是因为它是全站唯一「匿名且直接写库」的接口：
 * 限流失效不会报错，只会静默地把表写满，属于最不容易被发现的故障。
 */
class TrafficRateLimitTest {

    private final TrafficService trafficService = mock(TrafficService.class);
    private final ClientIpManager clientIpManager = mock(ClientIpManager.class);
    private final IpRateLimitManager ipRateLimitManager = mock(IpRateLimitManager.class);

    private TrafficController controller(boolean enabled) {
        TrafficRateLimitProperties properties = new TrafficRateLimitProperties();
        properties.setEnabled(enabled);
        // 显式给值，避免将来默认值调整让断言失去意义
        properties.setMinuteLimit(60);
        properties.setDailyLimit(3000);
        return new TrafficController(trafficService, clientIpManager, ipRateLimitManager, properties);
    }

    private static TrafficReportRequest reportRequest() {
        TrafficReportRequest request = new TrafficReportRequest();
        request.setPagePath("/posts");
        request.setVisitorId("v-1");
        return request;
    }

    private static MockHttpServletRequest httpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/traffic/report");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        return request;
    }

    @Test
    @DisplayName("关闭开关时不调用限流器，直接写库")
    void disabledSkipsRateLimit() {
        controller(false).report(reportRequest(), httpRequest());

        verifyNoInteractions(ipRateLimitManager);
        verify(trafficService).report(reportRequest());
    }

    @Test
    @DisplayName("开启时按解析后的真实 IP 做双窗口限流")
    void enabledChecksResolvedIp() {
        MockHttpServletRequest httpRequest = httpRequest();
        when(clientIpManager.resolveClientIp("203.0.113.7", "203.0.113.7", "203.0.113.7, 10.0.0.1"))
                .thenReturn("203.0.113.7");

        controller(true).report(reportRequest(), httpRequest);

        // 键前缀错一个字符就会静默变成另一个计数器（例如连带限掉登录），因此逐字断言
        verify(ipRateLimitManager).assertAllowed(
                eq("203.0.113.7"),
                eq(TrafficRateLimitConstant.REPORT_KEY_PREFIX),
                eq(60),
                eq(3000),
                eq(TrafficRateLimitConstant.REPORT_ACTION));
        verify(trafficService).report(reportRequest());
    }

    @Test
    @DisplayName("超限时抛 A0501，且不写库（限流必须早于写库）")
    void overLimitRejectsBeforeWriting() {
        MockHttpServletRequest httpRequest = httpRequest();
        when(clientIpManager.resolveClientIp(anyString(), anyString(), anyString())).thenReturn("203.0.113.7");
        doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR, "太频繁了"))
                .when(ipRateLimitManager).assertAllowed(anyString(), anyString(), eq(60), eq(3000), anyString());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller(true).report(reportRequest(), httpRequest));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(trafficService);
    }
}

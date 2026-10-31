package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 游客 AI 提问的 IP 级限流（通用限流器的业务适配器）
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimitManager {

    /** 游客提问限流的 Redis 键前缀（与通用实现的业务前缀约定一致，不同场景靠前缀区分） */
    private static final String KEY_PREFIX = "studio:ai:rate:ip:";

    private final IpRateLimitManager ipRateLimitManager;

    private final AiProperties aiProperties;

    /**
     * 游客提问前的限流检查
     *
     * @param ip 客户端 IP；为 null 或空时由通用实现直接放行，拿不到 IP 不能变成拒绝所有人
     * @throws com.bhu.runshistudioweb.exception.BusinessException 超过任一窗口上限时抛 A0501；Redis 不可用时通用实现降级放行，不抛异常
     */
    public void assertAllowed(String ip) {
        // 总开关：测试环境必须能关掉它（MockMvc 下所有请求同源，不关会集体误杀）
        Boolean enabled = aiProperties.getGuestIpLimitsEnabled();
        if (enabled != null && !enabled) {
            return;
        }
        ipRateLimitManager.assertAllowed(ip, KEY_PREFIX,
                aiProperties.getGuestIpMinuteLimit(), aiProperties.getGuestIpDailyLimit(), "提问");
    }
}

package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 游客 AI 提问的 IP 级限流（通用限流器的业务适配器）
 *
 * 只管游客：登录用户按用户计数，限流对他们既没必要（有配额兜底）也不公平（同出口 IP 会互相拖累）。
 * 游客没有身份，只能拿「从哪台机器来」当临时身份。
 *
 * 本类只有薄壳：双窗口限流在 {@link IpRateLimitManager} 里，这里只负责读配置、拼前缀、给动作名。
 * 键规范与 TTL 以通用实现为准，不要复制一份——两边不一致的后果是「某个入口永远拦不住」。
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
     * 必须放在最外层：配额预检、检索、模型调用、新建会话之前。
     * 超限的请求不该触发一次按文本量计费的 Embedding，也不该留下一堆空会话。
     *
     * 坑：开关字段是包装类型 Boolean，可能为 null。必须写成「非 null 且为 false 才放行」，
     * 直接 {@code if (!enabled)} 会拆箱抛空指针，而配置缺失本该按默认开启处理。
     *
     * @param ip 客户端 IP；为 null 或空时由通用实现直接放行，拿不到 IP 不能变成拒绝所有人
     * @throws com.bhu.runshistudioweb.exception.BusinessException 超过任一窗口上限时抛 A0501；
     *         Redis 不可用时通用实现降级放行，不抛异常
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

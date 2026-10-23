package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 游客 AI 提问的 IP 级限流（ IpRateLimitManager 的业务适配器）
//
// 只管游客：登录用户走 studio.ai.query-limit（按用户计数，与 IP 无关），
// 限流对他们既没必要（有配额兜底）也不公平（同一出口 IP 的多个用户会互相拖累）。
//
// 为什么只剩一个薄壳：双窗口限流的通用实现（Lua 原子自增 + 双窗口 + 降级纪律）
// 已上移到 IpRateLimitManager，登录 / 注册限流与这里共用同一套——本类只负责
// 「读 AI 配置、拼业务前缀、起个 AI 场景的动作名」。键规范、TTL 计算、降级行为
// 都以通用实现为准，不要在这里复制一份。
//
// 预留状态：AI 咨询当前要求登录（见 AiChatServiceImpl 的准入），
// 游客提问路径暂时不可达；若将来重新开放匿名提问，直接注入本类调用即可。
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimitManager {

    // 游客提问限流的 Redis 键前缀（与通用实现的业务前缀约定一致）
    private static final String KEY_PREFIX = "studio:ai:rate:ip:";

    private final IpRateLimitManager ipRateLimitManager;

    private final AiProperties aiProperties;

    // 游客提问前的限流检查
    //
    // 调用位置必须在最外层（配额预检、检索、模型调用之前）：
    // 超限的请求不该触发后面的检索与 Embedding——那才是真正的开销。
    //
    // @param ip 客户端 IP（为 null 或空时由通用实现放行：拿不到 IP 不能变成「拒绝所有人」）
    // @throws com.bhu.runshistudioweb.exception.BusinessException 超过任一窗口上限时抛出 A0501
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

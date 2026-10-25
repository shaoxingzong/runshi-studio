package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 游客 AI 提问的 IP 级限流（通用限流器的业务适配器）
 *
 * <p>只管游客：登录用户走 studio.ai.query-limit（按用户计数，与 IP 无关），
 * 限流对他们既没必要（有配额兜底）也不公平（同一出口 IP 的多个用户会互相拖累）。
 * 之所以要按 IP 兜底，是因为游客没有身份，配额无从谈起——只能拿「从哪台机器来」
 * 当临时身份。分钟窗口挡脚本连续刷，日窗口挡「每分钟几次、持续一整天」的慢速占用，
 * 两种窗口对应两种攻击形态，缺一都有明显漏洞。
 *
 * <p>为什么本类只有一个薄壳：双窗口限流的通用实现（Lua 脚本原子自增 + 双窗口 + 降级）
 * 已经在通用的 IP 限流器里，登录与注册限流共用同一套。本类只负责
 * 「读 AI 配置、拼业务前缀、起一个 AI 场景的动作名」。键规范、TTL 计算、降级行为
 * 都以通用实现为准，不要在这里复制一份——复制之后两边迟早不一致，
 * 而限流不一致的后果是「某个入口永远拦不住」。
 *
 * <p>当前状态：AI 咨询要求登录，游客提问路径暂时不可达；若将来重新开放匿名提问，
 * 直接注入本类调用即可，不需要改动通用实现。
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
     * <p>为什么这样设计：调用位置必须在最外层——配额预检、检索、模型调用之前。
     * 超限的请求不该触发后面的检索与 Embedding，那才是真正的开销：
     * 一次 Embedding 是一次按文本量计费的外部 HTTP，为注定被拒的请求付这笔钱毫无意义。
     * 同理，它也必须在「新建会话」之前，否则被限流的请求会留下一堆空会话。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先看总开关：关掉时直接放行，一个 Redis 命令都不发。
     *     开关存在是因为测试环境必须能关掉它——MockMvc 下所有请求同源，
     *     不关会在跑到第 6 个用例时集体误杀，那是假失败，会掩盖真实问题；</li>
     *     <li>② 交给通用实现做双窗口判定，本类只提供「前缀 + 两个上限 + 动作名」。
     *     动作名会出现在超限提示里（「提问过于频繁」），它属于业务语义，只能由本类给。</li>
     * </ol>
     *
     * <p>容易踩的坑：开关字段是包装类型 Boolean，可能为 null（配置写空）。
     * 判断必须写成「非 null 且为 false 才放行」，不能直接 {@code if (!enabled)}——
     * 那样 null 会拆箱抛空指针，而配置缺失本该是「按默认开启」处理。
     *
     * @param ip 客户端 IP。为 null 或空时由通用实现直接放行——
     *           拿不到 IP（例如反向代理没配好转发头）不能变成「拒绝所有人」；
     *           传错成「带端口」的地址也不会报错，只是按另一个 key 计数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 超过任一窗口上限时抛出 A0501；
     *         Redis 不可用时通用实现会降级放行，不抛异常
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

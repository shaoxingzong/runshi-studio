package com.bhu.runshistudioweb.manager;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 创建型接口的请求幂等令牌（发帖 / 评论 / 知识库手工录入）
 *
 * <p>为什么不用数据库唯一键：同一个人可以合法地连续发两条内容不同甚至相同的帖子，
 * 用内容 hash 或「作者+标题」做唯一键会误杀「复制模板再发一遍」的正常操作，
 * 逻辑删除列与唯一键叠加还会让 SQL 变复杂。有天然业务主键的场景（点赞 / 举报 /
 * 作答 / 绑定 / 通知 / 账号唯一性）继续用数据库唯一键，不走这里。
 *
 * <p>设计取舍：
 * <ul>
 *     <li>只在业务校验通过之后、落库之前消费令牌，参数错误的请求不占用窗口；</li>
 *     <li>SETNX + TTL 原子完成，重复请求返回固定文案，<b>不</b>缓存并回放首次结果
 *         （那需要存返回值、处理序列化与过期，对这个项目是过度设计）；</li>
 *     <li>Redis 不可用时一律放行：可用性与「限流 / 配额」的降级纪律一致。
 *         此时只剩前端 submitting 守卫兜底，重复插入一条内容属于可接受的退化；</li>
 *     <li>令牌按「用户 + 业务域」隔离，避免不同表单撞同一个 id，也避免拿别人的令牌挡人。</li>
 * </ul>
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyManager {

    /** 幂等窗口：覆盖一次请求的网络往返与用户重试，不需要覆盖异步 AI 审核 */
    private static final Duration TOKEN_TTL = Duration.ofSeconds(30);

    private static final String KEY_PREFIX = "studio:idem:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 消费一个幂等令牌：第一次返回 true，窗口内重复返回 false（调用方应拒绝）
     *
     * @param userId 当前登录用户 ID；由服务端取会话，绝不接受前端传入
     * @param domain 业务域（如 post / comment / knowledge），不同域互不影响
     * @param requestId 前端生成的一次性令牌；为空时直接放行（兼容旧客户端）
     * @return true 表示可以继续执行；false 表示重复请求
     */
    public boolean acquire(long userId, String domain, String requestId) {
        if (StrUtil.isBlank(requestId)) {
            // 旧版本前端 / 直接调接口：没有令牌就退回「仅靠前端守卫」，不阻断业务
            return true;
        }
        String key = KEY_PREFIX + domain + ":" + userId + ":" + StrUtil.trim(requestId);
        try {
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", TOKEN_TTL);
            // setIfAbsent 在少数客户端/代理下可能返回 null：按「拿不到结果」放行，与降级一致
            return !Boolean.FALSE.equals(ok);
        } catch (Exception e) {
            log.warn("幂等令牌不可用，本次放行（由前端守卫兜底）| domain={} | userId={}",
                    domain, userId, e);
            return true;
        }
    }

    /** 消费令牌并在重复时抛业务异常：调用方一行接入即可 */
    public void assertFirstRequest(long userId, String domain, String requestId) {
        if (!acquire(userId, domain, requestId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS_ERROR,
                    "请求正在处理中，请勿重复提交");
        }
    }
}

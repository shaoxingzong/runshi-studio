package com.bhu.runshistudioweb.manager;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bhu.runshistudioweb.constant.AiQueryCountConstant;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiQueryCountManager#reset 的单元测试
 *
 * 重点锁住一件事：重置必须同时清 DB 与 Redis。
 * 只清 DB 的话，未回刷的增量仍会被 merge 算进用量，用户重置完还是满的——
 * 这是本功能最容易写错、且肉眼看不出来的地方。
 *
 * @author shaoshing
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiQueryCountManagerResetTest {

    private static final long USER_ID = 90001L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SysUserMapper sysUserMapper;

    private AiQueryCountManager manager;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // update 是「被调用即视为成功」的动作，显式给返回值避免 Mockito 默认 0 干扰断言。
        // 第二个参数写 any(UpdateWrapper.class)：泛型擦除后裸 any() 匹配不上重载
        lenient().when(sysUserMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        manager = new AiQueryCountManager(stringRedisTemplate, sysUserMapper);
    }

    /** 造一个指定已用次数的用户 */
    private SysUser userWith(int aiQueryCount) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setAiQueryCount(aiQueryCount);
        return user;
    }

    @Test
    @DisplayName("重置会同时清 DB 与 Redis 键")
    void resetClearsBothDbAndRedis() {
        // 重置前：DB 基准 5，Redis 增量 0（已回刷完）
        when(sysUserMapper.selectById(USER_ID)).thenReturn(userWith(5));
        when(valueOperations.get(anyString())).thenReturn(null);

        int before = manager.reset(USER_ID);

        assertEquals(5, before, "应返回重置前的用量");
        verify(sysUserMapper, atLeastOnce()).update(any(), any(UpdateWrapper.class));
        verify(stringRedisTemplate, atLeastOnce()).delete(AiQueryCountConstant.keyOf(USER_ID));
    }

    @Test
    @DisplayName("Redis 里还有未回刷增量时，重置前的用量要把增量算进去")
    void resetCountsRedisDeltaInBeforeValue() {
        // 场景：用户问了 3 次，增量还在 Redis 里没落库；DB 基准 2
        when(sysUserMapper.selectById(USER_ID)).thenReturn(userWith(2));
        when(valueOperations.get(anyString())).thenReturn("3");

        int before = manager.reset(USER_ID);

        assertEquals(5, before, "重置前用量 = DB 2 + Redis 3");
        // 两处都必须被清：DB 置 0、Redis 键删除
        verify(sysUserMapper, atLeastOnce()).update(any(), any(UpdateWrapper.class));
        verify(stringRedisTemplate, atLeastOnce()).delete(AiQueryCountConstant.keyOf(USER_ID));
    }

    @Test
    @DisplayName("清完之后 merge 必须为 0")
    void mergeIsZeroAfterReset() {
        // 模拟「清完之后」的存储状态：DB 为 0、Redis 无键
        when(sysUserMapper.selectById(USER_ID)).thenReturn(userWith(0));
        when(valueOperations.get(anyString())).thenReturn(null);

        assertEquals(0, manager.merge(USER_ID, 0),
                "重置后 merge 必须为 0，否则用户看到额度还是满的");
    }

    @Test
    @DisplayName("Redis 读取失败时不影响重置（降级为只清 DB）")
    void resetToleratesRedisFailure() {
        when(sysUserMapper.selectById(USER_ID)).thenReturn(userWith(4));
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        // 不抛异常即为通过：重置是管理动作，Redis 抖动不该让它失败
        int before = manager.reset(USER_ID);
        assertEquals(4, before);
        verify(sysUserMapper, atLeastOnce()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    @DisplayName("用户不存在时 readDbCount 按 0 处理，不抛异常")
    void resetHandlesMissingUser() {
        when(sysUserMapper.selectById(USER_ID)).thenReturn(null);
        when(valueOperations.get(anyString())).thenReturn("2");

        int before = manager.reset(USER_ID);
        // DB 视为 0，Redis 有 2 → 重置前用量为 2
        assertEquals(2, before);
    }
}

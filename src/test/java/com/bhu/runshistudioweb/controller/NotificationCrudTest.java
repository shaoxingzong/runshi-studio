package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioNotificationMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import com.bhu.runshistudioweb.service.NotificationService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站内通知 · 接口契约测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    @Resource
    private NotificationService notificationService;

    @Resource
    private StudioNotificationMapper notificationMapper;

    /** 夹具：刻意不注入而自己 new——QuizTestSupport 不是 Spring bean （它依赖 MockMvc，而 @Component 会被不带 MockMvc 的测试上下文扫到导致启动失败，缘由写在那个类的注释里）。 */
    private QuizTestSupport support;

    /** 两名普通用户 + 一名管理员（越权与「只读到自己」需要两个平级账号） */
    private long userIdA;
    private long userIdB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        userIdA = support.createUser(UserRoleConstant.USER);
        userIdB = support.createUser(UserRoleConstant.USER);
        tokenA = support.tokenOf(userIdA);
        tokenB = support.tokenOf(userIdB);
    }

    /** 直插一条别人的通知（绕过投递逻辑，专注契约） */
    private long insertNotification(long receiverId, String type, boolean read) {
        StudioNotification notification = new StudioNotification();
        notification.setReceiverId(receiverId);
        notification.setType(type);
        notification.setBizType("POST");
        notification.setBizId(1L);
        notification.setTitle("别人的通知");
        notification.setContent("不属于你");
        notification.setIsRead(read ? 1 : 0);
        notificationMapper.insert(notification);
        return notification.getId();
    }

    private long unreadInDb(long userId) {
        return notificationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudioNotification>()
                        .eq(StudioNotification::getReceiverId, userId)
                        .eq(StudioNotification::getIsRead, 0));
    }

    // 鉴权边界

    @Test
    @DisplayName("鉴权：未登录访问全部接口返回 A0201")
    void anonymousIsRejected() {
        assertEquals("A0201", support.code(support.getBody("/notification/page", null)));
        assertEquals("A0201", support.code(support.getBody("/notification/unread-count", null)));
        assertEquals("A0201", support.code(support.postJson("/notification/read", "{\"id\":1}", null)));
        assertEquals("A0201", support.code(support.postJson("/notification/read-all", "{}", null)));
    }

    @Test
    @DisplayName("越权：标记他人通知已读按不存在处理（A0402，不暴露存在性）")
    void othersNotificationIsInvisible() {
        long foreignId = insertNotification(userIdB, "POST_APPROVED", false);

        String body = support.postJson("/notification/read", "{\"id\":" + foreignId + "}", tokenA);
        assertEquals("A0402", support.code(body), "他人通知必须按不存在处理：" + body);
        assertEquals("通知不存在", support.message(body), "提示必须与「真的不存在」一致，不泄露归属");
        // 且那条通知仍是未读（没有被顺手标记）
        StudioNotification untouched = notificationMapper.selectById(foreignId);
        assertNotNull(untouched);
        assertEquals(0, untouched.getIsRead(), "越权标记不能生效");
    }

    @Test
    @DisplayName("隔离：列表只返回自己的通知")
    void listOnlyMine() {
        long mine = insertNotification(userIdA, "POST_APPROVED", false);
        insertNotification(userIdB, "POST_APPROVED", false);

        String body = support.getBody("/notification/page?current=1&pageSize=10", tokenA);
        assertEquals("00000", support.code(body));
        var records = support.data(body).get("records");
        assertEquals(1, records.size(), "只能看到自己的通知：" + body);
        assertEquals(String.valueOf(mine), records.get(0).get("id").asText());
        assertTrue(support.data(body).get("records").get(0).get("typeDesc").asText().length() > 0,
                "typeDesc 必须由服务端填充（前端不维护映射）");
    }

    // 未读计数与已读

    @Test
    @DisplayName("未读计数：只数自己的未读")
    void unreadCountIsAccurate() {
        insertNotification(userIdA, "POST_APPROVED", false);
        insertNotification(userIdA, "COMMENT_REPLIED", false);
        insertNotification(userIdA, "POST_REJECTED", true); // 已读的不计
        insertNotification(userIdB, "POST_APPROVED", false); // 别人的不计

        String body = support.getBody("/notification/unread-count", tokenA);
        assertEquals("00000", support.code(body));
        assertEquals(2, support.data(body).asLong(), "未读数应只含自己的未读：" + body);
    }

    @Test
    @DisplayName("单条已读：计数减一，重复已读幂等")
    void markReadWorks() {
        long id = insertNotification(userIdA, "POST_APPROVED", false);
        assertEquals(1, unreadInDb(userIdA));

        String first = support.postJson("/notification/read", "{\"id\":" + id + "}", tokenA);
        assertEquals("00000", support.code(first));
        assertEquals(0, unreadInDb(userIdA), "已读后未读数应归零（该用户仅此一条）");

        // 重复已读：接口仍然成功，不报错、不刷新已读时间
        String second = support.postJson("/notification/read", "{\"id\":" + id + "}", tokenA);
        assertEquals("00000", support.code(second), "重复已读应幂等成功");
        StudioNotification reloaded = notificationMapper.selectById(id);
        assertEquals(1, reloaded.getIsRead());
        assertNotNull(reloaded.getReadAt(), "已读时间必须落库");
    }

    @Test
    @DisplayName("全部已读：返回本次更新条数，第二次返回 0")
    void markAllReadReturnsUpdatedCount() {
        insertNotification(userIdA, "POST_APPROVED", false);
        insertNotification(userIdA, "COMMENT_REPLIED", false);
        insertNotification(userIdB, "POST_APPROVED", false); // 别人的不动

        String first = support.postJson("/notification/read-all", "{}", tokenA);
        assertEquals("00000", support.code(first));
        assertEquals(2, support.data(first).asInt(), "只更新自己的未读：" + first);
        assertEquals(0, unreadInDb(userIdA));

        String second = support.postJson("/notification/read-all", "{}", tokenA);
        assertEquals("00000", support.code(second));
        assertEquals(0, support.data(second).asInt(), "没有未读可更新时返回 0");
    }

    @Test
    @DisplayName("过滤：isRead=0 只返回未读")
    void filterUnreadOnly() {
        long unreadId = insertNotification(userIdA, "POST_APPROVED", false);
        insertNotification(userIdA, "COMMENT_REPLIED", true);

        String body = support.getBody("/notification/page?isRead=0", tokenA);
        var records = support.data(body).get("records");
        assertEquals(1, records.size(), "isRead=0 只应返回未读：" + body);
        assertEquals(String.valueOf(unreadId), records.get(0).get("id").asText());
    }

    // 投递策略（内部方法直接测）

    @Test
    @DisplayName("投递幂等：同一事件重复投递只产生一条（唯一键兜底）")
    void duplicateNotifyIsIdempotent() {
        for (int i = 0; i < 3; i++) {
            notificationService.notify(userIdA, userIdB, "COMMENT_REPLIED",
                    "POST", 42L, "有人回复了你的评论", "内容");
        }
        assertEquals(1, unreadInDb(userIdA), "重复投递必须被唯一键收敛成一条");
    }

    @Test
    @DisplayName("不给自己发：receiver 与 actor 相同时静默跳过")
    void selfNotifyIsSkipped() {
        notificationService.notify(userIdA, userIdA, "COMMENT_REPLIED",
                "POST", 42L, "自己回复自己", "内容");
        assertEquals(0, unreadInDb(userIdA), "不能给自己发通知");
    }
}

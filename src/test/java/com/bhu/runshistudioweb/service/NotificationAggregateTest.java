package com.bhu.runshistudioweb.service;

import com.bhu.runshistudioweb.mapper.StudioNotificationMapper;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞通知 · 聚合写入测试
 *
 * <p>直接调 Service（不走 HTTP、不触发 AI 审核），专注验证
 * INSERT ... ON DUPLICATE KEY UPDATE 的聚合语义与并发安全性。
 *
 * @author shaoshing
 */
@SpringBootTest
@Transactional
class NotificationAggregateTest {

    private static final String ACCOUNT_PREFIX = "T35agg_";

    @Resource
    private NotificationService notificationService;

    @Resource
    private StudioNotificationMapper notificationMapper;

    @Resource
    private StudioPostLikeMapper likeMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private long authorId;
    private long actorId;
    private long postId;

    @BeforeEach
    void setUp() {
        authorId = createUser();
        actorId = createUser();
        // 点赞关系表只按 post_id 统计，不校验帖子是否存在；用雪花 ID 造一个孤立帖子即可
        postId = System.nanoTime();
        // 清掉这条帖子可能残留的点赞与通知，保证用例互不干扰
        jdbcTemplate.update("DELETE FROM studio_post_like WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM studio_notification WHERE biz_id = ? AND type = 'POST_LIKED'", postId);
    }

    @Test
    @DisplayName("首次点赞插入一条；再次点赞只更新同一条，赞数与未读状态同步刷新")
    void aggregateUpsertUpdatesSameRow() {
        insertLike(actorId);
        notificationService.notifyLikeAggregated(authorId, actorId, postId, "聚合测试帖");

        List<StudioNotification> rows = rows();
        assertEquals(1, rows.size(), "首次点赞应产生一条 POST_LIKED");
        StudioNotification first = rows.get(0);
        assertEquals(NotificationTypeEnum.POST_LIKED.getValue(), first.getType());
        assertEquals("POST", first.getBizType());
        assertEquals(postId, first.getBizId());
        assertTrue(first.getTitle().contains("1 个赞"), first.getTitle());
        assertEquals(0, first.getIsRead());

        // 作者读过这条通知（直接改库：markRead 走会话，这里没有登录态）
        jdbcTemplate.update("""
                UPDATE studio_notification SET is_read = 1, read_at = NOW()
                WHERE id = ? AND receiver_id = ?
                """, first.getId(), authorId);

        // 第二个用户点赞：同一条通知被更新，赞数变 2，重新置为未读
        long secondActorId = createUser();
        insertLike(secondActorId);
        notificationService.notifyLikeAggregated(authorId, secondActorId, postId, "聚合测试帖");

        List<StudioNotification> after = rows();
        assertEquals(1, after.size(), "同一帖子只能有一条点赞通知（不刷屏）");
        assertEquals(first.getId(), after.get(0).getId(), "应更新原行，而不是新建一条");
        assertTrue(after.get(0).getTitle().contains("2 个赞"), after.get(0).getTitle());
        assertEquals(0, after.get(0).getIsRead(), "新互动要把已读的通知重新点亮");
        assertNull(after.get(0).getReadAt(), "重置未读时 read_at 必须清空");
    }

    @Test
    @DisplayName("不给自己发：作者赞自己的帖子不产生通知")
    void selfLikeDoesNotNotify() {
        insertLike(authorId);
        notificationService.notifyLikeAggregated(authorId, authorId, postId, "聚合测试帖");

        assertTrue(rows().isEmpty(), "不能给自己发通知");
    }

    @Test
    @DisplayName("赞数取自点赞关系表（真相源），评论通知的 postId 反查不依赖通知内容")
    void countComesFromLikeTable() {
        insertLike(actorId);
        insertLike(createUser());
        insertLike(createUser());
        notificationService.notifyLikeAggregated(authorId, actorId, postId, "聚合测试帖");

        assertEquals(1, rows().size());
        assertTrue(rows().get(0).getTitle().contains("3 个赞"), rows().get(0).getTitle());

        // 纯参数版本：推送线程没有会话上下文时也能统计未读数
        assertEquals(1, notificationService.countUnreadFor(authorId));
        assertEquals(0, notificationService.countUnreadFor(actorId), "不能把别人的未读数算进来");
    }

    /** 造一个登录账号（只用到 user.id，登录态由 Service 的纯参数方法规避） */
    private long createUser() {
        String account = ACCOUNT_PREFIX + (System.nanoTime() % 100000000);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, user_account, user_password, user_name, user_role,
                                      user_status, created_at, updated_at, created_by, updated_by, deleted_at)
                VALUES (?, ?, 'x', ?, 'user', 0, NOW(), NOW(), 0, 0, 0)
                """, System.nanoTime(), account, account);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE user_account = ?", Long.class, account);
        assertTrue(id != null);
        return id;
    }

    private void insertLike(long userId) {
        StudioPostLike like = new StudioPostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        likeMapper.insert(like);
    }

    private List<StudioNotification> rows() {
        return notificationMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudioNotification>()
                .eq(StudioNotification::getReceiverId, authorId)
                .eq(StudioNotification::getType, NotificationTypeEnum.POST_LIKED.getValue())
                .eq(StudioNotification::getBizId, postId));
    }
}

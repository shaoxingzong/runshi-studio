package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.NotificationPushManager;
import com.bhu.runshistudioweb.mapper.StudioNotificationMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;
import com.bhu.runshistudioweb.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 站内通知服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl extends ServiceImpl<StudioNotificationMapper, StudioNotification>
        implements NotificationService {

    /** 未读 */
    private static final int READ_STATUS_UNREAD = 0;

    /** 已读 */
    private static final int READ_STATUS_READ = 1;

    /** 分页每页条数的默认值（与其它 C 端列表一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;

    /** 评论类通知的来源类型（biz_id 存的是评论 ID，需要反查帖子 ID 才能跳转） */
    private static final String BIZ_TYPE_COMMENT = "COMMENT";

    /** 帖子/点赞类通知的来源类型 */
    private static final String BIZ_TYPE_POST = "POST";

    /** 点赞关系：累计赞数的真相源（通知里不维护计数器） */
    private final StudioPostLikeMapper studioPostLikeMapper;

    /** 评论表：批量反查评论类通知所属帖子 */
    private final StudioPostCommentMapper studioPostCommentMapper;

    /** SSE 实时推送（未读数）；通知写入在事务内，推送必须等提交后 */
    private final NotificationPushManager notificationPushManager;

    // 投递（内部能力）

    /** 投递一条站内通知（由业务触发点在同一事务内调用） */
    @Override
    public void notify(Long receiverId, Long actorId, String type,
                       String bizType, Long bizId, String title, String content) {
        if (receiverId == null || type == null) {
            log.warn("通知投递参数不完整，已跳过 | receiverId={} | type={}", receiverId, type);
            return;
        }
        // 不给自己发：回复自己的评论是真实场景。放在这里而非三个调用处各判一遍
        if (Objects.equals(receiverId, actorId)) {
            return;
        }

        StudioNotification notification = new StudioNotification();
        notification.setReceiverId(receiverId);
        notification.setType(type);
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setIsRead(READ_STATUS_UNREAD);
        // createdBy 留空由 MyMetaObjectHandler 自动填充；这里不手动设置

        try {
            this.save(notification);
            // 只有真的写入了才推；撞唯一键（幂等重复）或写失败都不推，避免红点假亮
            pushUnreadAfterCommit(receiverId);
        } catch (DuplicateKeyException e) {
            log.debug("通知已存在，按幂等跳过 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId);
        } catch (Exception e) {
            log.error("通知投递失败，不影响主业务 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId, e);
        }
    }

    /** 点赞通知：按「作者 + 帖子」聚合，原子 UPSERT 并刷新赞数 */
    @Override
    public void notifyLikeAggregated(long receiverId, long actorId, long postId, String postTitle) {
        // 不给自己发：与 notify() 同一口径，放在最前面省掉一次无意义的 count
        if (receiverId == actorId) {
            return;
        }
        try {
            // 赞数以点赞关系表为准：评论删除、通知被标记已读等都不会让口径漂移
            Long likes = studioPostLikeMapper.selectCount(new LambdaQueryWrapper<StudioPostLike>()
                    .eq(StudioPostLike::getPostId, postId));
            long total = likes == null ? 0L : likes;
            String safeTitle = StrUtil.isBlank(postTitle) ? "你的帖子" : postTitle;
            String title = "《" + safeTitle + "》收到 " + total + " 个赞";
            String content = "有人赞了你的帖子，累计 " + total + " 个赞";

            // 手写 SQL 不走 MyBatis-Plus 的 ASSIGN_ID，主键要自己生成
            baseMapper.upsertLikeAggregate(IdWorker.getId(), receiverId,
                    NotificationTypeEnum.POST_LIKED.getValue(), BIZ_TYPE_POST, postId,
                    title, content, LocalDateTime.now());
            // 聚合更新同样要刷新红点（重置为未读后数字可能变化）
            pushUnreadAfterCommit(receiverId);
        } catch (Exception e) {
            // 通知失败绝不影响点赞主流程
            log.error("点赞聚合通知写入失败，不影响点赞 | receiverId={} | postId={}",
                    receiverId, postId, e);
        }
    }

    /**
     * 通知写入后刷新实时红点（事务提交后才推）
     *
     * <p>notify() 在点赞/评论审核的业务事务内被调用。若直接在事务里发 SSE，
     * 事务一旦回滚，用户会看到一个并不存在的未读数；这里统一挂 afterCommit。
     * 没有事务同步时（例如某些异步回调路径）直接推一次。
     *
     * <p>推送失败一律吞掉：前端保留低频轮询兜底，绝不能反过来影响业务事务。
     */
    private void pushUnreadAfterCommit(long receiverId) {
        Runnable push = () -> {
            try {
                // 推送线程里没有 Sa-Token 会话上下文，用纯参数版本统计
                notificationPushManager.pushUnread(receiverId, countUnreadFor(receiverId));
            } catch (Exception e) {
                log.debug("通知 SSE 推送失败，交给轮询兜底 | receiverId={} | 原因={}",
                        receiverId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    /** 统计指定用户未读数（纯参数版本，推送线程没有会话上下文） */
    @Override
    public long countUnreadFor(long receiverId) {
        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioNotification::getReceiverId, receiverId);
        wrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        // count 生成的 SQL 会被 MP 自动追加 deleted_at = 0
        return this.count(wrapper);
    }

    // 消费（C 端）

    /** 我的通知列表（新的在前） */
    @Override
    public Page<NotificationVO> listMy(NotificationQueryRequest request) {
        // 登录人从会话取，绝不接受前端传 userId——否则任何人都能翻别人的通知
        long userId = currentUserId();
        NotificationQueryRequest query = request == null ? new NotificationQueryRequest() : request;

        // 分页兜底纠正（与其它列表同一处理方式）：非法值收敛而不是报错
        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), 50L);

        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        // receiver_id 是数据隔离的底线：少这一个 eq，就会把全站通知发给所有人
        wrapper.eq(StudioNotification::getReceiverId, userId);
        // isRead 过滤：null 表示全部。条件重载的第一个参数为 false 时该条件不拼进 SQL
        wrapper.eq(query.getIsRead() != null, StudioNotification::getIsRead, query.getIsRead());
        // 新的在前（走 idx_receiver_read 的 created_at，不起 filesort）
        wrapper.orderByDesc(StudioNotification::getCreatedAt);

        Page<StudioNotification> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /** 我的未读通知数（门户铃铛红点） */
    @Override
    public long unreadCount() {
        // 两个条件缺一不可：少 receiver_id 会统计全站，少 isRead 会把已读也算进来
        return countUnreadFor(currentUserId());
    }

    /** 标记单条通知已读（幂等） */
    @Override
    public void markRead(Long id) {
        // 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "通知 id 不合法");
        long userId = currentUserId();

        StudioNotification notification = this.getById(id);
        ThrowUtils.throwIf(notification == null, ErrorCode.NOT_FOUND_ERROR, "通知不存在");
        ThrowUtils.throwIf(!Objects.equals(notification.getReceiverId(), userId),
                ErrorCode.NOT_FOUND_ERROR, "通知不存在");

        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getId, id);
        // 这个条件就是幂等的来源：已是已读时匹配 0 行，read_at 保持不变
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        this.update(null, updateWrapper);
        // 其它标签页/设备上的红点也要即时归零（本页面由前端事件直接刷新）
        pushUnreadAfterCommit(userId);
    }

    /** 全部标记已读 */
    @Override
    public int markAllRead() {
        // 更新范围限定为当前登录用户：少了它会把全站用户的通知一起标记掉
        long userId = currentUserId();

        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getReceiverId, userId);
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        // getBaseMapper().update 返回受影响行数，正是本方法的返回值
        int rows = this.getBaseMapper().update(null, updateWrapper);
        if (rows > 0) {
            pushUnreadAfterCommit(userId);
        }
        return rows;
    }

    // 私有工具

    /** 取当前登录用户 ID */
    private long currentUserId() {
        // 登录态由 Sa-Token 维护，Controller 上的 @SaCheckLogin 已经拦过未登录的情况
        return StpUtil.getLoginIdAsLong();
    }

    /** 实体分页转 VO 分页 */
    private Page<NotificationVO> toVOPage(Page<StudioNotification> entityPage) {
        // 评论类通知的 bizId 是评论 ID，跳转需要帖子 ID：整页一次 IN 查询反查，避免逐条 N+1
        Map<Long, Long> commentToPost = resolveCommentPostIds(entityPage.getRecords());
        List<NotificationVO> records = entityPage.getRecords().stream()
                .map(entity -> toVO(entity, commentToPost))
                .toList();
        Page<NotificationVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /** 批量反查评论所属帖子；评论已被删除时映射里没有该 ID，VO 的 postId 保持 null（前端降级为不跳转） */
    private Map<Long, Long> resolveCommentPostIds(List<StudioNotification> entities) {
        Set<Long> commentIds = entities.stream()
                .filter(e -> BIZ_TYPE_COMMENT.equals(e.getBizType()) && e.getBizId() != null)
                .map(StudioNotification::getBizId)
                .collect(Collectors.toSet());
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        List<StudioPostComment> comments = studioPostCommentMapper.selectList(
                new LambdaQueryWrapper<StudioPostComment>()
                        .in(StudioPostComment::getId, commentIds)
                        .select(StudioPostComment::getId, StudioPostComment::getPostId));
        Map<Long, Long> result = new HashMap<>();
        for (StudioPostComment comment : comments) {
            if (comment.getPostId() != null) {
                result.put(comment.getId(), comment.getPostId());
            }
        }
        return result;
    }

    /** 实体转 VO：typeDesc 按枚举填充；评论类补上帖子 ID 供跳转 */
    private NotificationVO toVO(StudioNotification entity, Map<Long, Long> commentToPost) {
        NotificationVO vo = new NotificationVO();
        BeanUtils.copyProperties(entity, vo);
        NotificationTypeEnum typeEnum = NotificationTypeEnum.of(entity.getType());
        // 枚举查不到时退回原始取值：宁可显示英文常量，也不要让 typeDesc 变成 null
        vo.setTypeDesc(typeEnum == null ? entity.getType() : typeEnum.getDesc());
        if (BIZ_TYPE_COMMENT.equals(entity.getBizType()) && entity.getBizId() != null) {
            vo.setPostId(commentToPost.get(entity.getBizId()));
        }
        return vo;
    }
}

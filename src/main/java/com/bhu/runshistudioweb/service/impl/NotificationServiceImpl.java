package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioNotificationMapper;
import com.bhu.runshistudioweb.model.dto.notification.NotificationQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioNotification;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.vo.notification.NotificationVO;
import com.bhu.runshistudioweb.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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
        } catch (DuplicateKeyException e) {
            log.debug("通知已存在，按幂等跳过 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId);
        } catch (Exception e) {
            log.error("通知投递失败，不影响主业务 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId, e);
        }
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
        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        // 两个条件缺一不可：少 receiver_id 会统计全站，少 isRead 会把已读也算进来
        wrapper.eq(StudioNotification::getReceiverId, currentUserId());
        wrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        // count 生成的 SQL 会被 MP 自动追加 deleted_at = 0，逻辑删除的通知不计入
        return this.count(wrapper);
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
        return this.getBaseMapper().update(null, updateWrapper);
    }

    // 私有工具

    /** 取当前登录用户 ID */
    private long currentUserId() {
        // 登录态由 Sa-Token 维护，Controller 上的 @SaCheckLogin 已经拦过未登录的情况
        return StpUtil.getLoginIdAsLong();
    }

    /** 实体分页转 VO 分页 */
    private Page<NotificationVO> toVOPage(Page<StudioNotification> entityPage) {
        List<NotificationVO> records = entityPage.getRecords().stream()
                .map(this::toVO)
                .toList();
        Page<NotificationVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /** 实体转 VO：typeDesc 按枚举填充 */
    private NotificationVO toVO(StudioNotification entity) {
        NotificationVO vo = new NotificationVO();
        BeanUtils.copyProperties(entity, vo);
        NotificationTypeEnum typeEnum = NotificationTypeEnum.of(entity.getType());
        // 枚举查不到时退回原始取值：宁可显示英文常量，也不要让 typeDesc 变成 null
        vo.setTypeDesc(typeEnum == null ? entity.getType() : typeEnum.getDesc());
        return vo;
    }
}

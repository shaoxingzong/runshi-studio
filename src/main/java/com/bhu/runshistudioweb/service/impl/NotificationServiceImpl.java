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
 * author: shaoshing
 *
 * <p><b>{@link #notify} 的三条约束（幂等 / 不给自己发 / 失败不拖垮主业务）
 * 全部实现在这一个方法里</b>——触发点只管「出了什么事」，投递策略只有一份。
 * 这是本项目一贯的分工：策略集中一处，调用方各管各的业务语义。
 *
 * <p><b>为什么 notify 不加 @Transactional</b>：它必须活在调用方的事务里
 * （与主业务同生共死），自己开事务反而破坏这一点。Spring 的事务是线程绑定的，
 * 调用方开着事务时，本方法的写自动加入同一事务，不需要任何注解。
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

    // ==================== 投递（内部能力） ====================

    @Override
    public void notify(Long receiverId, Long actorId, String type,
                       String bizType, Long bizId, String title, String content) {
        // ① 基础校验：接收者与类型是投递的最低要求，缺了直接放弃（记日志而非抛异常——
        //    调用方是业务代码，不该因为一个附属通知收到异常）
        if (receiverId == null || type == null) {
            log.warn("通知投递参数不完整，已跳过 | receiverId={} | type={}", receiverId, type);
            return;
        }
        // ② 不给自己发：回复自己的评论是真实场景。放在这里而非三个调用处各判一遍
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
            // 幂等：同一事件重复投递（审核接口重放、重试）撞唯一键即视为已投递。
            // 这是预期路径不是错误——debug 级即可，不需要 warn 打扰运维
            log.debug("通知已存在，按幂等跳过 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId);
        } catch (Exception e) {
            // 兜底：任何其它失败（连接断、锁等待超时）都只记日志。
            // 主业务优先——不能因为一条附属通知让用户的发帖/审核/回复失败。
            // 注意：同事务内 catch 不抛异常，事务不会标记回滚，主业务的写照常提交
            log.error("通知投递失败，不影响主业务 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId, e);
        }
    }

    // ==================== 消费（C 端） ====================

    @Override
    public Page<NotificationVO> listMy(NotificationQueryRequest request) {
        long userId = currentUserId();
        NotificationQueryRequest query = request == null ? new NotificationQueryRequest() : request;

        // 分页兜底纠正（与其它列表同一处理方式）：非法值收敛而不是报错
        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), 50L);

        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioNotification::getReceiverId, userId);
        // isRead 过滤：null 表示全部。条件重载的第一个参数为 false 时该条件不拼进 SQL
        wrapper.eq(query.getIsRead() != null, StudioNotification::getIsRead, query.getIsRead());
        // 新的在前（走 idx_receiver_read 的 created_at，不起 filesort）
        wrapper.orderByDesc(StudioNotification::getCreatedAt);

        Page<StudioNotification> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    @Override
    public long unreadCount() {
        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioNotification::getReceiverId, currentUserId());
        wrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        return this.count(wrapper);
    }

    @Override
    public void markRead(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "通知 id 不合法");
        long userId = currentUserId();

        // 先取实体做归属校验：他人（或已删除）的通知一律按「不存在」处理——
        // 不暴露「这条通知其实存在，只是不是你的」
        StudioNotification notification = this.getById(id);
        ThrowUtils.throwIf(notification == null, ErrorCode.NOT_FOUND_ERROR, "通知不存在");
        ThrowUtils.throwIf(!Objects.equals(notification.getReceiverId(), userId),
                ErrorCode.NOT_FOUND_ERROR, "通知不存在");

        // 幂等更新：只把「未读」的置为已读。重复调用不刷新 read_at——
        // 第一次阅读时间才有意义，第二次点不该把「刚读过」变成假的新鲜事
        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getId, id);
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        this.update(null, updateWrapper);
    }

    @Override
    public int markAllRead() {
        long userId = currentUserId();

        // 只更新未读的：已是已读的不计入返回条数（重复调用第二次返回 0，
        // 前端可以据此判断「有没有变化」）。
        // 走 BaseMapper.update 而非 ServiceImpl.update：后者只返回 boolean，
        // 而「实际更新了几条」是这个接口的返回值语义
        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getReceiverId, userId);
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        return this.getBaseMapper().update(null, updateWrapper);
    }

    // ==================== 私有工具 ====================

    /**
     * 取当前登录用户 ID
     *
     * <p>消费侧全部方法都挂 {@code @SaCheckLogin}，能走到这里必然已登录，
     * 与 {@code UserServiceImpl} 取 loginId 同一方式。
     */
    private long currentUserId() {
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

    /** 实体转 VO：typeDesc 按枚举填充（前端不维护类型值 → 中文的映射） */
    private NotificationVO toVO(StudioNotification entity) {
        NotificationVO vo = new NotificationVO();
        BeanUtils.copyProperties(entity, vo);
        NotificationTypeEnum typeEnum = NotificationTypeEnum.of(entity.getType());
        vo.setTypeDesc(typeEnum == null ? entity.getType() : typeEnum.getDesc());
        return vo;
    }
}

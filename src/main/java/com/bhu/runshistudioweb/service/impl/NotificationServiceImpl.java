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
 * notify 的三条约束（幂等 / 不给自己发 / 失败不拖垮主业务）全部实现在这一个方法里：
 * 触发点只管「出了什么事」，投递策略只有一份。
 *
 * notify 不加 @Transactional：它必须活在调用方的事务里。Spring 的事务是线程绑定的，
 * 调用方开着事务时本方法的写会自动加入同一事务；若加了 REQUIRES_NEW，就会出现
 * 「主业务回滚了、通知却已经发出去」。
 *
 * title / content 存的是快照而不是模板引用：通知记录的是「那一刻发生了什么」，
 * 帖子事后被改名、评论被删除，都不该改写用户已经收到的文字。
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

    // ==================== 投递（内部能力）====================

    /**
     * 投递一条站内通知（由业务触发点在同一事务内调用）
     *
     * 幂等靠唯一键 uk_receiver_biz，撞键说明已经投递过，静默返回即可；不给自己发，
     * 是因为「回复自己的评论」是真实场景；失败不拖垮主业务，是因为通知只是附属产物。
     *
     * 坑：catch 住异常却不重新抛出，事务不会被标记为回滚，主业务的写会照常提交——这正是想要的。
     *
     * @param receiverId 接收者用户 ID；为 null 时直接跳过
     * @param actorId 触发者用户 ID，可为 null；与 receiverId 相等时跳过
     * @param type 通知类型，取值见 NotificationTypeEnum；为 null 时直接跳过
     * @param bizId 来源业务 ID，可为 null；与 bizType、type、receiverId 一起构成幂等键
     * @param title 通知标题（一句话摘要，快照）
     * @param content 通知正文（快照；驳回类带理由）
     */
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

        // ③ 组装实体：title / content 存的是快照文本，
        // 业务数据后来被改名或删除，都不会改写这条已发出的通知
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
            // ④ 幂等：同一事件重复投递（审核接口重放、重试）撞唯一键即视为已投递。
            // 这是预期路径不是错误——debug 级即可，不需要 warn 打扰运维
            log.debug("通知已存在，按幂等跳过 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId);
        } catch (Exception e) {
            // 兜底：任何其它失败（连接断、锁等待超时）都只记日志。
            // 主业务优先——不能因为一条附属通知让用户的发帖 / 审核 / 回复失败。
            // 注意：同事务内 catch 不抛异常，事务不会标记回滚，主业务的写照常提交
            log.error("通知投递失败，不影响主业务 | receiverId={} | type={} | bizId={}",
                    receiverId, type, bizId, e);
        }
    }

    // ==================== 消费（C 端）====================

    /**
     * 我的通知列表（新的在前）
     *
     * 当前登录人从 Sa-Token 取，绝不接受前端传 userId——否则任何人都能翻别人的通知。
     * 过滤条件里固定带 receiver_id，这是数据隔离的底线，少一个 eq 就会把全站通知发给所有人。
     *
     * 坑：orderByDesc(createdAt) 依赖 idx_receiver_read 的最后一段，按接收者查时索引本身
     * 就能提供有序结果；改成按 updatedAt 排序这条索引就用不上，通知多了会明显变慢。
     *
     * @param request 查询条件（isRead 过滤 + 分页），允许为 null（按全部 + 第一页处理）
     * @return 分页结果，记录为 NotificationVO；没有通知时 records 为空列表、total 为 0
     */
    @Override
    public Page<NotificationVO> listMy(NotificationQueryRequest request) {
        // ① 登录人从会话取，绝不接受前端传 userId——否则任何人都能翻别人的通知
        long userId = currentUserId();
        NotificationQueryRequest query = request == null ? new NotificationQueryRequest() : request;

        // ② 分页兜底纠正（与其它列表同一处理方式）：非法值收敛而不是报错
        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), 50L);

        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        // ③ receiver_id 是数据隔离的底线：少这一个 eq，就会把全站通知发给所有人
        wrapper.eq(StudioNotification::getReceiverId, userId);
        // isRead 过滤：null 表示全部。条件重载的第一个参数为 false 时该条件不拼进 SQL
        wrapper.eq(query.getIsRead() != null, StudioNotification::getIsRead, query.getIsRead());
        // ④ 新的在前（走 idx_receiver_read 的 created_at，不起 filesort）
        wrapper.orderByDesc(StudioNotification::getCreatedAt);

        Page<StudioNotification> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /**
     * 我的未读通知数（门户铃铛红点）
     *
     * 单独一个 COUNT 接口，而不是让前端拉列表自己数：铃铛要 60 秒轮询一次，
     * 拉整页数据只为数个数，流量与数据库压力都不划算。
     * 两个条件都必须带：只带 receiver_id 会把已读算进去，只带 is_read 会统计全站未读数。
     *
     * @return 未读条数；没有未读时返回 0，不返回 null
     */
    @Override
    public long unreadCount() {
        LambdaQueryWrapper<StudioNotification> wrapper = new LambdaQueryWrapper<>();
        // ① 两个条件缺一不可：少 receiver_id 会统计全站，少 isRead 会把已读也算进来
        wrapper.eq(StudioNotification::getReceiverId, currentUserId());
        wrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        // ② count 生成的 SQL 会被 MP 自动追加 deleted_at = 0，逻辑删除的通知不计入
        return this.count(wrapper);
    }

    /**
     * 标记单条通知已读（幂等）
     *
     * 他人的通知一律按「不存在」处理（A0402）而不是「无权访问」：后者等于告诉对方
     * 「这条通知确实存在，只是不是你的」，属于不必要的信息泄露。
     * 更新条件里带 is_read = 未读，重复点击时更新 0 行，read_at 保持第一次的值。
     *
     * 坑：不要写成「先查出来、set 已读、再 updateById」——那样两次调用都会刷新 read_at，
     * 并发下两个请求都认为自己是第一次。用带条件的 UPDATE 把判断交给数据库才是原子的。
     *
     * @param id 通知 ID，必须大于 0
     * @throws BusinessException id 不合法抛 A0401，通知不存在或不属于当前用户抛 A0402
     */
    @Override
    public void markRead(Long id) {
        // ① 参数校验：非法 id 不该产生任何数据库往返
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "通知 id 不合法");
        long userId = currentUserId();

        // ② 先取实体做归属校验：他人（或已删除）的通知一律按「不存在」处理——
        // 不暴露「这条通知其实存在，只是不是你的」
        StudioNotification notification = this.getById(id);
        ThrowUtils.throwIf(notification == null, ErrorCode.NOT_FOUND_ERROR, "通知不存在");
        ThrowUtils.throwIf(!Objects.equals(notification.getReceiverId(), userId),
                ErrorCode.NOT_FOUND_ERROR, "通知不存在");

        // ③ 幂等更新：只把「未读」的置为已读。重复调用不刷新 read_at——
        // 第一次阅读时间才有意义，第二次点不该把「刚读过」变成假的新鲜事
        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getId, id);
        // 这个条件就是幂等的来源：已是已读时匹配 0 行，read_at 保持不变
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        this.update(null, updateWrapper);
    }

    /**
     * 全部标记已读
     *
     * 只更新未读的那些，所以重复调用第二次返回 0——这个返回值有意义，前端据此决定要不要刷新列表。
     * 因此走 BaseMapper.update 而不是 ServiceImpl 的 update：后者只返回 boolean。
     *
     * 坑：条件里必须带 is_read = 未读。少了它，已读通知的 read_at 会被整体刷新成「现在」，
     * 「最近读过的通知」排序全部错乱，而且没有任何报错。
     *
     * @return 本次被置为已读的条数（已是已读的不计入，重复调用第二次返回 0）
     */
    @Override
    public int markAllRead() {
        // ① 更新范围限定为当前登录用户：少了它会把全站用户的通知一起标记掉
        long userId = currentUserId();

        // ② 只更新未读的：已是已读的不计入返回条数（重复调用第二次返回 0，
        // 前端可以据此判断「有没有变化」）。
        // 走 BaseMapper.update 而非 ServiceImpl.update：后者只返回 boolean，
        // 而「实际更新了几条」是这个接口的返回值语义
        LambdaUpdateWrapper<StudioNotification> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(StudioNotification::getReceiverId, userId);
        updateWrapper.eq(StudioNotification::getIsRead, READ_STATUS_UNREAD);
        updateWrapper.set(StudioNotification::getIsRead, READ_STATUS_READ);
        updateWrapper.set(StudioNotification::getReadAt, LocalDateTime.now());
        // ③ getBaseMapper().update 返回受影响行数，正是本方法的返回值
        return this.getBaseMapper().update(null, updateWrapper);
    }

    // ==================== 私有工具 ====================

    /**
     * 取当前登录用户 ID
     *
     * 消费侧的四个方法都由 Controller 上的 @SaCheckLogin 拦住，能走到这里必然已登录，
     * 因此直接取 loginId 不需要再判空。登录态只有一个来源，不能各处自己解析。
     *
     * @return 当前登录用户 ID
     */
    private long currentUserId() {
        // 登录态由 Sa-Token 维护，Controller 上的 @SaCheckLogin 已经拦过未登录的情况
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 实体分页转 VO 分页
     *
     * 新建一个 Page 而不是在原对象上改：泛型不同，无法就地转换。
     * total / current / size 三个元数据必须显式传过去，前端分页器依赖它们。
     *
     * @param entityPage 实体分页
     * @return VO 分页
     */
    private Page<NotificationVO> toVOPage(Page<StudioNotification> entityPage) {
        List<NotificationVO> records = entityPage.getRecords().stream()
                .map(this::toVO)
                .toList();
        Page<NotificationVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /**
     * 实体转 VO：typeDesc 按枚举填充
     *
     * 中文描述由后端填：前端自己维护一份「类型值 → 中文」的映射，后端加一个类型，
     * 前端就会显示成裸的英文常量，而且没人知道该去改哪里。
     *
     * @param entity 通知实体
     * @return 带 typeDesc 的 VO
     */
    private NotificationVO toVO(StudioNotification entity) {
        NotificationVO vo = new NotificationVO();
        BeanUtils.copyProperties(entity, vo);
        NotificationTypeEnum typeEnum = NotificationTypeEnum.of(entity.getType());
        // 枚举查不到时退回原始取值：宁可显示英文常量，也不要让 typeDesc 变成 null
        vo.setTypeDesc(typeEnum == null ? entity.getType() : typeEnum.getDesc());
        return vo;
    }
}

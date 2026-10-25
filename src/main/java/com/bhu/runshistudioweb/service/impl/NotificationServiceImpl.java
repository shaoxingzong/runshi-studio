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
 * notify 的三条约束（幂等 / 不给自己发 / 失败不拖垮主业务）
 * 全部实现在这一个方法里——触发点只管「出了什么事」，投递策略只有一份。
 * 这是本项目一贯的分工：策略集中一处，调用方各管各的业务语义。
 *
 * 为什么 notify 不加 @Transactional：它必须活在调用方的事务里
 * （与主业务同生共死），自己开事务反而破坏这一点。
 * Spring 的事务是线程绑定的：调用方开着事务时，本方法的写会自动加入同一事务，
 * 不需要任何注解。若加了 REQUIRES_NEW（挂起外层事务、另开一个独立事务），
 * 就会出现「主业务回滚了、通知却已经发出去」——用户收到一条指向不存在结果的通知。
 *
 * title / content 是快照而不是模板引用：通知记录的是「那一刻发生了什么」。
 * 帖子事后被改名、评论被删除，都不该改写用户已经收到的通知文字。
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
     * <p>为什么这样设计：三条约束必须集中在一处实现。
     * 幂等（重复调用结果一致）靠唯一键 uk_receiver_biz
     * （receiver_id + type + biz_type + biz_id + deleted_at）实现，
     * 撞键就说明这条通知已经投递过，静默返回即可；
     * 不给自己发，是因为「回复自己的评论」是真实场景，给自己发通知纯属噪音；
     * 失败不拖垮主业务，是因为通知只是附属产物——用户发帖、审核、回复才是主流程，
     * 不能因为一条通知写失败就让这些操作整体报错。
     * 这三条如果分散到每个触发点去写，迟早会有一处漏掉，所以统一收在这里。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 基础校验：接收者与类型是投递的最低要求，缺了直接放弃。
     *         注意这里是记日志 + return，不是抛异常——调用方是业务代码，
     *         不该因为一个附属通知收到异常；</li>
     *     <li>② 不给自己发：放在这里而不是三个调用处各判一遍，
     *         单一防线比三处重复更可靠，将来新增触发点也自动继承这条规则；</li>
     *     <li>③ 组装实体：title / content 直接存快照文本，不存模板 ID，
     *         这样业务数据后来怎么变都不会改写已发出的通知；</li>
     *     <li>④ 落库并分两种异常处理：唯一键冲突是「已投递过」的正常路径，
     *         用 debug 级别记录；其它异常（连接断、锁等待超时）记 error，
     *         但不向外抛，让主业务继续。</li>
     * </ol>
     *
     * <p>容易踩的坑：第 ④ 步 catch 住异常却不重新抛出，事务不会被标记为回滚，
     * 主业务的写会照常提交。这正是想要的：通知失败不该牵连主业务。
     * 但也要清楚它的代价——若失败原因是数据库连接已断，
     * 主业务接下来的写同样会失败，那时会由主业务自己回滚，结果依然是可接受的。
     *
     * @param receiverId 接收者用户 ID（sys_user.id）；为 null 时直接跳过
     * @param actorId 触发者用户 ID，可为 null（系统动作没有操作者）；
     *                与 receiverId 相等时跳过
     * @param type 通知类型，取值见 NotificationTypeEnum；为 null 时直接跳过
     * @param bizType 来源业务类型：POST / COMMENT / USER，可为 null
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
     * <p>为什么这样设计：当前登录人从 Sa-Token 取，绝不接受前端传 userId——
     * 否则任何人都能翻别人的通知。过滤条件里固定带上 receiver_id，
     * 这是数据隔离的底线；少了它，一个 eq 的疏忽就会把全站通知发给所有人。
     * 分页参数做兜底纠正而不是报错，与其它列表保持同一处理方式。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 取当前登录用户 ID，作为不可被请求参数覆盖的过滤条件；</li>
     *     <li>② 纠正分页参数（页码下限 1、pageSize 上限 50）；</li>
     *     <li>③ 拼条件：receiver_id 必带，isRead 传了才过滤；</li>
     *     <li>④ 按创建时间倒序分页查询并转 VO，同时填上类型的中文描述。</li>
     * </ol>
     *
     * <p>容易踩的坑：orderByDesc(createdAt) 依赖 idx_receiver_read
     * (receiver_id, is_read, deleted_at, created_at) 的最后一段，
     * 按接收者查时索引本身就能提供有序结果，不走 filesort。
     * 若把排序改成 updatedAt，这条索引就用不上了，通知多了会明显变慢。
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
     * <p>为什么这样设计：单独一个 COUNT 接口，而不是让前端拿列表自己数。
     * 铃铛要 60 秒轮询一次，拉整页数据只为数个数，流量与数据库压力都不划算；
     * COUNT 走 idx_receiver_read 的最左前缀，代价极低。
     * 两个条件都必须带：只带 receiver_id 会把已读也算进去，
     * 只带 is_read 更糟——那会统计全站未读数。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拼两个等值条件（接收者 + 未读）；</li>
     *     <li>② count 直接返回条数，逻辑删除的行由 MyBatis-Plus 自动排除。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里返回 long，是因为 MyBatis-Plus 的 count 就是 long，
     * 而且它是接口返回的计数而不是 ID。VO 里的计数字段才需要注意用 Integer——
     * 本项目把 Long 统一序列化成字符串，图表拿到 "12" 会算错。
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
     * <p>为什么这样设计：两层讲究。
     * 第一层是归属校验——他人的通知一律按「不存在」处理（A0402），
     * 而不是返回「无权访问」。后者等于告诉对方「这条通知确实存在，只是不是你的」，
     * 属于不必要的信息泄露。
     * 第二层是只刷新第一次的 read_at：更新条件里带上 is_read = 未读，
     * 重复点击时更新 0 行，read_at 保持第一次的值。否则「什么时候读的」就没有意义了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正数；</li>
     *     <li>② 取实体做归属校验：不存在或不属于当前用户，都按 A0402 处理；</li>
     *     <li>③ 用 UpdateWrapper 把「未读」改成「已读」，
     *         条件里带 is_read = 未读，从而保证重复调用不会覆盖 read_at。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要写成「先查出来、set 已读、再 updateById」。
     * 那样两次调用都会刷新 read_at，而且并发下两个请求都认为自己是第一次。
     * 用带条件的 UPDATE 把判断交给数据库，才是原子的。
     *
     * @param id 通知 ID，必须大于 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法时抛出（A0401）；
     *         通知不存在、或不属于当前登录用户时抛出（A0402）
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
     * <p>为什么这样设计：只更新未读的那些，所以重复调用第二次返回 0。
     * 这个返回值有意义——前端据此判断「有没有发生变化」，决定要不要刷新列表。
     * 因此这里走 BaseMapper.update 而不是 ServiceImpl 的 update：
     * 后者只返回 boolean，拿不到「实际更新了几条」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 取当前登录用户 ID，作为更新范围，避免把别人的通知也标记掉；</li>
     *     <li>② 拼更新条件：接收者 + 未读；</li>
     *     <li>③ 执行 UPDATE 并返回受影响行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：条件里必须带 is_read = 未读。少了它，
     * 已读通知的 read_at 会被整体刷新成「现在」，
     * 于是「最近读过的通知」排序全部错乱，而且没有任何报错。
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
     * 消费侧的四个方法都由 Controller 上的 @SaCheckLogin 拦住，
     * 能走到这里必然已登录，因此直接取 loginId 不需要再判空。
     * 与 UserServiceImpl 取 loginId 是同一方式：登录态只有一个来源，不能各处自己解析。
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
     * 为什么由后端填中文描述：前端如果自己维护一份「类型值 → 中文」的映射，
     * 后端加一个通知类型，前端就会显示成裸的英文常量，
     * 而且没人知道该去改哪里。放在后端，加类型时只改一处枚举。
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

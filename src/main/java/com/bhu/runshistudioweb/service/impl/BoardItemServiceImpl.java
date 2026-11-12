package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.IdempotencyManager;
import com.bhu.runshistudioweb.mapper.StudioBoardItemMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAddRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemAssignRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemQueryRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemStatusRequest;
import com.bhu.runshistudioweb.model.dto.board.BoardItemUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioBoardItem;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.BoardItemStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.vo.board.BoardItemOverviewVO;
import com.bhu.runshistudioweb.model.vo.board.BoardItemVO;
import com.bhu.runshistudioweb.service.BoardItemService;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作室内部事项看板服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardItemServiceImpl extends ServiceImpl<StudioBoardItemMapper, StudioBoardItem>
        implements BoardItemService {

    /** 已完成列最多展示多少条：历史堆积不该把看板淹没，统计里仍给全量 */
    private static final int DONE_COLUMN_LIMIT = 20;

    /** 无截止日期的事项在列内排最后（用一个足够远的哨兵值，避免 MySQL NULL 排序语义差异） */
    private static final LocalDateTime FAR_FUTURE = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final StudioMemberService studioMemberService;

    private final SysUserMapper sysUserMapper;

    private final IdempotencyManager idempotencyManager;

    private final OperationLogService operationLogService;

    /**
     * 当前访问者上下文：管理员不一定有在队成员档案，因此两者分开判断。
     *
     * @param userId  登录账号 ID
     * @param admin   是否管理员
     * @param member  在队成员档案（管理员可以没有）
     */
    private record Viewer(long userId, boolean admin, StudioMember member) {
    }

    @Override
    public Long add(BoardItemAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "请求不能为空");
        long userId = StpUtil.getLoginIdAsLong();
        BoardItemStatusEnum status = resolveStatus(request.getStatus(), false);
        String title = StrUtil.trim(request.getTitle());
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "事项标题不能为空");
        Long assigneeId = validateAssignee(request.getAssigneeMemberId());

        // 校验全部通过后才消费令牌：坏请求不占用 30s 窗口
        idempotencyManager.assertFirstRequest(userId, "board-item", request.getRequestId());

        StudioBoardItem item = new StudioBoardItem();
        item.setTitle(title);
        item.setDescription(StrUtil.trimToNull(request.getDescription()));
        item.setStatus(status.getValue());
        item.setAssigneeMemberId(assigneeId);
        item.setDeadline(request.getDeadline());
        item.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        // 直接以「已完成」状态新建时也要有完成时间，否则统计与排序会出现空洞
        if (status.isDone()) {
            item.setCompletedAt(LocalDateTime.now());
        }
        ThrowUtils.throwIf(!this.save(item), ErrorCode.OPERATION_ERROR, "新增事项失败");

        operationLogService.record(OperationTypeEnum.BOARD_ITEM_ADD, item.getId(), item.getTitle(),
                "状态=" + status.getLabel() + "；截止="
                        + (request.getDeadline() == null ? "无" : request.getDeadline()));
        return item.getId();
    }

    @Override
    public void update(BoardItemUpdateRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR, "事项 ID 不能为空");
        StudioBoardItem existing = getExisting(request.getId());

        LambdaUpdateWrapper<StudioBoardItem> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioBoardItem::getId, request.getId());
        if (request.getTitle() != null) {
            String title = StrUtil.trim(request.getTitle());
            ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "事项标题不能为空");
            wrapper.set(StudioBoardItem::getTitle, title);
        }
        if (request.getDescription() != null) {
            wrapper.set(StudioBoardItem::getDescription, StrUtil.trimToNull(request.getDescription()));
        }
        if (Boolean.TRUE.equals(request.getClearDeadline())) {
            wrapper.set(StudioBoardItem::getDeadline, null);
        } else if (request.getDeadline() != null) {
            wrapper.set(StudioBoardItem::getDeadline, request.getDeadline());
        }
        if (request.getSortOrder() != null) {
            wrapper.set(StudioBoardItem::getSortOrder, request.getSortOrder());
        }
        ThrowUtils.throwIf(!this.update(wrapper), ErrorCode.OPERATION_ERROR, "编辑事项失败");

        operationLogService.record(OperationTypeEnum.BOARD_ITEM_UPDATE, existing.getId(),
                request.getTitle() == null ? existing.getTitle() : request.getTitle(), "编辑事项内容");
    }

    @Override
    public void delete(Long id) {
        StudioBoardItem existing = getExisting(id);
        ThrowUtils.throwIf(!this.removeById(id), ErrorCode.OPERATION_ERROR, "下线事项失败");
        operationLogService.record(OperationTypeEnum.BOARD_ITEM_DELETE, id, existing.getTitle(), "逻辑删除");
    }

    @Override
    public void assign(BoardItemAssignRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR, "事项 ID 不能为空");
        StudioBoardItem existing = getExisting(request.getId());
        Long assigneeId = validateAssignee(request.getAssigneeMemberId());

        LambdaUpdateWrapper<StudioBoardItem> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioBoardItem::getId, request.getId())
                .set(StudioBoardItem::getAssigneeMemberId, assigneeId);
        ThrowUtils.throwIf(!this.update(wrapper), ErrorCode.OPERATION_ERROR, "指派失败");

        String name = assigneeId == null ? "未指派" : memberNames(Set.of(assigneeId)).getOrDefault(assigneeId, "成员");
        operationLogService.record(OperationTypeEnum.BOARD_ITEM_ASSIGN, existing.getId(),
                existing.getTitle(), "负责人=" + name);
    }

    @Override
    public void changeStatus(BoardItemStatusRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR, "事项 ID 不能为空");
        Viewer viewer = currentViewer();
        StudioBoardItem existing = getExisting(request.getId());
        BoardItemStatusEnum target = resolveStatus(request.getTargetStatus(), true);

        // 权限边界：管理员可改任意事项；成员只能推进指派给自己的那条
        if (!viewer.admin()) {
            ThrowUtils.throwIf(viewer.member() == null, ErrorCode.NO_AUTH_ERROR, "仅工作室成员可操作事项看板");
            ThrowUtils.throwIf(existing.getAssigneeMemberId() == null
                            || !Objects.equals(existing.getAssigneeMemberId(), viewer.member().getId()),
                    ErrorCode.NO_AUTH_ERROR, "只能推进指派给自己的事项");
        }

        // 幂等：目标与当前相同，什么都不做，也不重复写日志
        if (Objects.equals(existing.getStatus(), target.getValue())) {
            return;
        }

        LambdaUpdateWrapper<StudioBoardItem> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioBoardItem::getId, request.getId());
        // 乐观锁：前端带上打开页面时看到的状态，两个成员同时改同一条时后者会失败而不是静默覆盖
        if (request.getExpectedStatus() != null) {
            wrapper.eq(StudioBoardItem::getStatus, request.getExpectedStatus());
        }
        wrapper.set(StudioBoardItem::getStatus, target.getValue());
        if (target.isDone()) {
            wrapper.set(StudioBoardItem::getCompletedAt, LocalDateTime.now());
        } else {
            // 从已完成回退时清空完成时间，否则「完成时间」会留在一条待办事项上
            wrapper.set(StudioBoardItem::getCompletedAt, null);
        }
        ThrowUtils.throwIf(!this.update(wrapper), ErrorCode.OPERATION_ERROR, "状态已变更，请刷新后重试");

        BoardItemStatusEnum from = BoardItemStatusEnum.of(existing.getStatus());
        operationLogService.record(OperationTypeEnum.BOARD_ITEM_STATUS, existing.getId(), existing.getTitle(),
                (from == null ? existing.getStatus() : from.getLabel()) + " → " + target.getLabel());
    }

    @Override
    public Page<BoardItemVO> page(BoardItemQueryRequest request) {
        BoardItemQueryRequest query = request == null ? new BoardItemQueryRequest() : request;
        Viewer viewer = currentViewer();
        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long size = query.getPageSize() == null || query.getPageSize() < 1 ? 10L : Math.min(query.getPageSize(), 50L);

        if (query.getStatus() != null) {
            resolveStatus(query.getStatus(), true);
        }
        LambdaQueryWrapper<StudioBoardItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotBlank(query.getTitle()), StudioBoardItem::getTitle, StrUtil.trim(query.getTitle()));
        wrapper.eq(query.getStatus() != null, StudioBoardItem::getStatus, query.getStatus());
        wrapper.eq(query.getAssigneeMemberId() != null,
                StudioBoardItem::getAssigneeMemberId, query.getAssigneeMemberId());
        if (Boolean.TRUE.equals(query.getOnlyOverdue())) {
            wrapper.ne(StudioBoardItem::getStatus, BoardItemStatusEnum.DONE.getValue())
                    .isNotNull(StudioBoardItem::getDeadline)
                    .lt(StudioBoardItem::getDeadline, LocalDateTime.now());
        }
        wrapper.orderByDesc(StudioBoardItem::getSortOrder)
                .orderByAsc(StudioBoardItem::getDeadline)
                .orderByDesc(StudioBoardItem::getCreatedAt);

        Page<StudioBoardItem> entityPage = this.page(new Page<>(current, size), wrapper);
        Map<Long, StudioMember> members = memberMap(entityPage.getRecords());
        Page<BoardItemVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(item -> toVO(item, members, viewer)).toList());
        return voPage;
    }

    @Override
    public BoardItemOverviewVO board() {
        Viewer viewer = currentViewer();
        List<StudioBoardItem> all = this.list(new LambdaQueryWrapper<StudioBoardItem>()
                .orderByDesc(StudioBoardItem::getSortOrder)
                .orderByAsc(StudioBoardItem::getDeadline)
                .orderByDesc(StudioBoardItem::getCreatedAt));
        Map<Long, StudioMember> members = memberMap(all);

        BoardItemOverviewVO overview = new BoardItemOverviewVO();
        int pending = 0;
        int overdue = 0;
        int myPending = 0;
        int doneCount = 0;
        LocalDateTime now = LocalDateTime.now();

        // 分列排序在内存里做：MySQL 的 ASC 会把 NULL（无截止）排在最前，
        // 而看板希望「有截止且越近越靠前、无截止排最后」。
        List<StudioBoardItem> todo = new java.util.ArrayList<>();
        List<StudioBoardItem> doing = new java.util.ArrayList<>();
        List<StudioBoardItem> done = new java.util.ArrayList<>();
        for (StudioBoardItem item : all) {
            BoardItemStatusEnum status = BoardItemStatusEnum.of(item.getStatus());
            boolean isOverdue = item.getDeadline() != null && now.isAfter(item.getDeadline());
            if (status == BoardItemStatusEnum.TODO) {
                todo.add(item);
            } else if (status == BoardItemStatusEnum.DOING) {
                doing.add(item);
            } else if (status == BoardItemStatusEnum.DONE) {
                done.add(item);
                doneCount++;
            } else {
                // 未知状态不丢数据：统一塞进待办列，由管理端修正
                todo.add(item);
            }
            if (status != BoardItemStatusEnum.DONE) {
                pending++;
                if (isOverdue) {
                    overdue++;
                }
                if (viewer.member() != null && Objects.equals(item.getAssigneeMemberId(), viewer.member().getId())) {
                    myPending++;
                }
            }
        }

        Comparator<StudioBoardItem> byDeadline = Comparator
                .comparing((StudioBoardItem item) -> item.getDeadline() == null ? FAR_FUTURE : item.getDeadline())
                .thenComparing(Comparator.comparingInt(
                        (StudioBoardItem item) -> item.getSortOrder() == null ? 0 : item.getSortOrder()).reversed());
        todo.sort(byDeadline);
        doing.sort(byDeadline);

        overview.setTodo(todo.stream().map(item -> toVO(item, members, viewer)).toList());
        overview.setDoing(doing.stream().map(item -> toVO(item, members, viewer)).toList());
        overview.setDone(done.stream()
                .sorted(Comparator.comparing(StudioBoardItem::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(DONE_COLUMN_LIMIT)
                .map(item -> toVO(item, members, viewer)).toList());
        overview.setPendingCount(pending);
        overview.setOverdueCount(overdue);
        overview.setMyPendingCount(myPending);
        overview.setDoneCount(doneCount);
        return overview;
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /** 取当前访问者：管理员无需在队档案，普通用户必须绑定在队档案 */
    private Viewer currentViewer() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        boolean admin = isAdmin(userId);
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        ThrowUtils.throwIf(!admin && member == null, ErrorCode.NO_AUTH_ERROR, "仅工作室成员可查看事项看板");
        return new Viewer(userId, admin, member);
    }

    /** 从 sys_user 直接读角色，不依赖 Sa-Token 的版本相关 API */
    private boolean isAdmin(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user != null && UserRoleConstant.ADMIN.equals(user.getUserRole());
    }

    private StudioBoardItem getExisting(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "事项 ID 不合法");
        StudioBoardItem item = this.getById(id);
        ThrowUtils.throwIf(item == null, ErrorCode.NOT_FOUND_ERROR, "事项不存在或已下线");
        return item;
    }

    private BoardItemStatusEnum resolveStatus(Integer value, boolean required) {
        if (value == null) {
            ThrowUtils.throwIf(required, ErrorCode.PARAMS_ERROR, "状态不能为空");
            return BoardItemStatusEnum.TODO;
        }
        BoardItemStatusEnum status = BoardItemStatusEnum.of(value);
        ThrowUtils.throwIf(status == null, ErrorCode.PARAMS_ERROR,
                "事项状态不合法，仅支持 " + BoardItemStatusEnum.valuesText());
        return status;
    }

    /** 负责人必须是在队成员；null 表示未指派，是合法值 */
    private Long validateAssignee(Long assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        StudioMember member = studioMemberService.getById(assigneeId);
        ThrowUtils.throwIf(member == null
                        || !Objects.equals(member.getMemberStatus(), MemberStatusEnum.IN_TEAM.getValue()),
                ErrorCode.PARAMS_ERROR, "负责人必须是在队成员");
        return assigneeId;
    }

    /** 一次 IN 查出全部负责人，避免看板每条卡片各查一次库的 N+1 */
    private Map<Long, StudioMember> memberMap(List<StudioBoardItem> items) {
        Set<Long> ids = items.stream()
                .map(StudioBoardItem::getAssigneeMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return studioMemberService.listByIds(ids).stream()
                .collect(Collectors.toMap(StudioMember::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, String> memberNames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (StudioMember member : studioMemberService.listByIds(ids)) {
            result.put(member.getId(), member.getName());
        }
        return result;
    }

    private BoardItemVO toVO(StudioBoardItem item, Map<Long, StudioMember> members, Viewer viewer) {
        BoardItemVO vo = new BoardItemVO();
        BeanUtils.copyProperties(item, vo);
        BoardItemStatusEnum status = BoardItemStatusEnum.of(item.getStatus());
        vo.setStatusLabel(status == null ? String.valueOf(item.getStatus()) : status.getLabel());
        if (item.getAssigneeMemberId() != null) {
            StudioMember member = members.get(item.getAssigneeMemberId());
            if (member != null) {
                vo.setAssigneeName(member.getName());
                vo.setAssigneeAvatar(member.getAvatar());
            }
        }
        vo.setOverdue(isOverdue(item));
        vo.setCanChangeStatus(viewer.admin() || (viewer.member() != null
                && Objects.equals(item.getAssigneeMemberId(), viewer.member().getId())));
        return vo;
    }

    /** 未完成且已过截止时间算逾期；已完成但完成时间晚于截止时间算「逾期完成」 */
    private boolean isOverdue(StudioBoardItem item) {
        if (item.getDeadline() == null) {
            return false;
        }
        BoardItemStatusEnum status = BoardItemStatusEnum.of(item.getStatus());
        if (status != null && status.isDone()) {
            return item.getCompletedAt() != null && item.getCompletedAt().isAfter(item.getDeadline());
        }
        return LocalDateTime.now().isAfter(item.getDeadline());
    }
}

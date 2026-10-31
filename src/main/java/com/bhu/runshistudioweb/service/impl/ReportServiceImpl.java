package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.StudioPostReportMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.report.ReportAddRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportHandleRequest;
import com.bhu.runshistudioweb.model.dto.report.ReportQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.enums.ReportStatusEnum;
import com.bhu.runshistudioweb.model.enums.ReportTargetTypeEnum;
import com.bhu.runshistudioweb.model.vo.report.ReportVO;
import com.bhu.runshistudioweb.service.NotificationService;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.PostCommentService;
import com.bhu.runshistudioweb.service.PostService;
import com.bhu.runshistudioweb.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内容举报服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl extends ServiceImpl<StudioPostReportMapper, StudioPostReport>
        implements ReportService {

    /** 处置动作：举报成立，内容撤下（转已驳回） */
    private static final String ACTION_RESOLVE = "resolve";

    /** 处置动作：举报成立，内容删除 */
    private static final String ACTION_DELETE = "delete";

    /** 处置动作：举报不成立，内容保留 */
    private static final String ACTION_DISMISS = "dismiss";

    /** 每页条数默认值与上限（与后台其它列表一致） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 评论正文摘要长度：够认出是哪句，又不把整段内容搬进列表 */
    private static final int COMMENT_SUMMARY_LENGTH = 60;

    private final StudioPostMapper studioPostMapper;

    private final StudioPostCommentMapper studioPostCommentMapper;

    private final SysUserMapper sysUserMapper;

    /** 删除动作复用现有级联删除：评论数回退、子回复清理、操作日志都在那边，重复实现必然漏一处 */
    private final PostService postService;

    private final PostCommentService postCommentService;

    private final NotificationService notificationService;

    private final OperationLogService operationLogService;

    // 用户端：提交

    /** 提交举报 */
    @Override
    public long submit(ReportAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");

        ReportTargetTypeEnum targetType = ReportTargetTypeEnum.of(StrUtil.trim(request.getTargetType()));
        ThrowUtils.throwIf(targetType == null, ErrorCode.PARAMS_ERROR,
                "举报对象类型只能是 " + ReportTargetTypeEnum.valuesText());
        ThrowUtils.throwIf(request.getTargetId() == null || request.getTargetId() <= 0,
                ErrorCode.PARAMS_ERROR, "举报对象 ID 不合法");

        String reason = StrUtil.trim(request.getReason());
        ThrowUtils.throwIf(StrUtil.isBlank(reason), ErrorCode.PARAMS_ERROR, "请填写举报理由");

        // 只允许举报对外可见的内容：不可见内容被举报会暴露「它其实存在」
        assertReportable(targetType, request.getTargetId());

        StudioPostReport report = new StudioPostReport();
        report.setTargetType(targetType.getValue());
        report.setTargetId(request.getTargetId());
        report.setReporterId(StpUtil.getLoginIdAsLong());
        report.setReason(reason);
        report.setStatus(ReportStatusEnum.PENDING.getValue());
        try {
            this.save(report);
        } catch (DuplicateKeyException e) {
            // 唯一键 uk_target_reporter 撞键即「同一人已举报过同一内容」
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "你已举报过该内容，请等待处理");
        }
        return report.getId();
    }

    /** 校验目标存在且已通过 */
    private void assertReportable(ReportTargetTypeEnum targetType, long targetId) {
        boolean visible = switch (targetType) {
            case POST -> {
                StudioPost post = studioPostMapper.selectById(targetId);
                yield post != null && Objects.equals(post.getStatus(), AuditStatusEnum.APPROVED.getValue());
            }
            case COMMENT -> {
                StudioPostComment comment = studioPostCommentMapper.selectById(targetId);
                yield comment != null && Objects.equals(comment.getStatus(), AuditStatusEnum.APPROVED.getValue());
            }
        };
        // 与「内容确实不存在」同一提示：不区分「不存在」与「未过审」，避免探测
        ThrowUtils.throwIf(!visible, ErrorCode.NOT_FOUND_ERROR, "内容不存在或已下线");
    }

    // 管理端：查询与处置

    /** 举报分页（默认待处理，时间倒序） */
    @Override
    public Page<ReportVO> listByPage(ReportQueryRequest request) {
        ReportQueryRequest query = request == null ? new ReportQueryRequest() : request;

        // 默认只看待处理：举报页的首要任务是清空队列
        int status = query.getStatus() == null ? ReportStatusEnum.PENDING.getValue() : query.getStatus();
        ThrowUtils.throwIf(ReportStatusEnum.of(status) == null, ErrorCode.PARAMS_ERROR,
                "举报状态只能是 " + ReportStatusEnum.valuesText());

        LambdaQueryWrapper<StudioPostReport> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostReport::getStatus, status);
        if (StrUtil.isNotBlank(query.getTargetType())) {
            ReportTargetTypeEnum targetType = ReportTargetTypeEnum.of(query.getTargetType().trim());
            ThrowUtils.throwIf(targetType == null, ErrorCode.PARAMS_ERROR,
                    "举报对象类型只能是 " + ReportTargetTypeEnum.valuesText());
            wrapper.eq(StudioPostReport::getTargetType, targetType.getValue());
        }
        wrapper.orderByDesc(StudioPostReport::getCreatedAt).orderByDesc(StudioPostReport::getId);

        long current = query.getCurrent() == null || query.getCurrent() < 1 ? 1L : query.getCurrent();
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), MAX_PAGE_SIZE);

        Page<StudioPostReport> entityPage = this.page(new Page<>(current, pageSize), wrapper);
        Page<ReportVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(toVOs(entityPage.getRecords()));
        return voPage;
    }

    /** 处置一条举报 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(ReportHandleRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();

        StudioPostReport report = this.getById(request.getId());
        ThrowUtils.throwIf(report == null, ErrorCode.NOT_FOUND_ERROR, "举报不存在");
        ThrowUtils.throwIf(!Objects.equals(report.getStatus(), ReportStatusEnum.PENDING.getValue()),
                ErrorCode.PARAMS_ERROR, "该举报已处理，不能重复处置");

        ReportTargetTypeEnum targetType = ReportTargetTypeEnum.of(report.getTargetType());
        ThrowUtils.throwIf(targetType == null, ErrorCode.PARAMS_ERROR, "举报目标类型异常，无法处置");

        String action = StrUtil.trim(request.getAction());
        ThrowUtils.throwIf(StrUtil.isBlank(action), ErrorCode.PARAMS_ERROR, "处置动作不能为空");
        TargetInfo target = loadTargetInfo(targetType, report.getTargetId());
        String handleResult = StrUtil.trimToNull(request.getHandleResult());

        boolean resolved;
        OperationTypeEnum logAction;
        switch (action) {
            case ACTION_RESOLVE -> {
                resolved = true;
                logAction = OperationTypeEnum.REPORT_RESOLVE;
                handleResult = StrUtil.blankToDefault(handleResult, "举报成立，内容已撤下");
                takeDown(targetType, target, operatorId, "举报受理：内容已撤下");
            }
            case ACTION_DELETE -> {
                resolved = true;
                logAction = OperationTypeEnum.REPORT_DELETE;
                handleResult = StrUtil.blankToDefault(handleResult, "举报成立，内容已删除");
                updateReportStatus(report.getId(), ReportStatusEnum.RESOLVED, operatorId, handleResult);
                if (Objects.equals(target.status(), AuditStatusEnum.APPROVED.getValue())) {
                    deleteTarget(targetType, report.getTargetId());
                } else {
                    log.info("举报目标已不在公开状态，跳过删除 | reportId={} | targetType={} | targetId={}",
                            report.getId(), targetType.getValue(), report.getTargetId());
                }
            }
            case ACTION_DISMISS -> {
                resolved = false;
                logAction = OperationTypeEnum.REPORT_DISMISS;
                handleResult = StrUtil.blankToDefault(handleResult, "经核实举报不成立，内容保留");
            }
            default -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "处置动作只能是 resolve / delete / dismiss");
        }

        // delete 分支已提前更新，这里用条件更新兜底（已处理则 0 行，不影响事务）
        updateReportStatus(report.getId(), resolved ? ReportStatusEnum.RESOLVED : ReportStatusEnum.DISMISSED,
                operatorId, handleResult);

        // 通知举报人：举报最怕「提交后毫无反馈」，这也是举报通道能否被继续使用的关键
        notifyReporter(report, target, resolved, handleResult);

        // 操作日志：举报处置是典型的「对别人内容的处置」，必须能查到是谁放行/撤下的
        operationLogService.record(logAction, report.getId(), target.title(),
                "举报 " + report.getTargetType() + "#" + report.getTargetId() + "；" + handleResult);
        log.info("管理员处置举报 | reportId={} | action={} | operatorId={}", report.getId(), action, operatorId);
    }

    /** 撤下内容：帖子/评论转为「已驳回」并写入理由 */
    private void takeDown(ReportTargetTypeEnum targetType, TargetInfo target, long operatorId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        if (targetType == ReportTargetTypeEnum.POST) {
            studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                    .eq(StudioPost::getId, target.id())
                    .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                    .set(StudioPost::getStatus, AuditStatusEnum.REJECTED.getValue())
                    .set(StudioPost::getRejectReason, reason)
                    .set(StudioPost::getAuditBy, operatorId)
                    .set(StudioPost::getAuditAt, now)
                    .set(StudioPost::getUpdatedBy, operatorId)
                    .set(StudioPost::getUpdatedAt, now));
            return;
        }

        int rows = studioPostCommentMapper.update(null, new LambdaUpdateWrapper<StudioPostComment>()
                .eq(StudioPostComment::getId, target.id())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.APPROVED.getValue())
                .set(StudioPostComment::getStatus, AuditStatusEnum.REJECTED.getValue())
                .set(StudioPostComment::getRejectReason, reason)
                .set(StudioPostComment::getAuditBy, operatorId)
                .set(StudioPostComment::getAuditAt, now)
                .set(StudioPostComment::getUpdatedBy, operatorId)
                .set(StudioPostComment::getUpdatedAt, now));
        if (rows > 0 && target.postId() != null) {
            studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                    .eq(StudioPost::getId, target.postId())
                    .setSql("comment_count = GREATEST(comment_count - " + rows + ", 0)"));
        }
    }

    /** 删除内容：复用现有级联删除 */
    private void deleteTarget(ReportTargetTypeEnum targetType, long targetId) {
        if (targetType == ReportTargetTypeEnum.POST) {
            postService.deletePost(targetId);
        } else {
            postCommentService.deleteComment(targetId);
        }
    }

    /** 条件更新举报状态（只有待处理才更新） */
    private void updateReportStatus(long reportId, ReportStatusEnum status, long operatorId, String handleResult) {
        this.update(new LambdaUpdateWrapper<StudioPostReport>()
                .eq(StudioPostReport::getId, reportId)
                .eq(StudioPostReport::getStatus, ReportStatusEnum.PENDING.getValue())
                .set(StudioPostReport::getStatus, status.getValue())
                .set(StudioPostReport::getHandleBy, operatorId)
                .set(StudioPostReport::getHandleAt, LocalDateTime.now())
                .set(StudioPostReport::getHandleResult, handleResult)
                .set(StudioPostReport::getUpdatedBy, operatorId)
                .set(StudioPostReport::getUpdatedAt, LocalDateTime.now()));
    }

    /** 通知举报人 */
    private void notifyReporter(StudioPostReport report, TargetInfo target, boolean resolved, String handleResult) {
        Long bizId = target.postId() != null ? target.postId() : report.getTargetId();
        String title = resolved ? "你的举报已受理" : "你的举报未成立";
        String content = resolved
                ? "你举报的内容已被撤下，感谢你帮助维护社区秩序。"
                : "经核实，你举报的内容暂无违规，已被保留。";
        notificationService.notify(report.getReporterId(), StpUtil.getLoginIdAsLong(),
                resolved ? NotificationTypeEnum.REPORT_RESOLVED.getValue()
                        : NotificationTypeEnum.REPORT_DISMISSED.getValue(),
                "POST", bizId, title, content + "（处理说明：" + handleResult + "）");
    }

    // 展示数据装配

    /** 实体分页转 VO（批量补目标标题与举报人昵称，避免 N+1） */
    private List<ReportVO> toVOs(List<StudioPostReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }

        Set<Long> postIds = new HashSet<>();
        Set<Long> commentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (StudioPostReport report : reports) {
            if (ReportTargetTypeEnum.POST.getValue().equals(report.getTargetType())) {
                postIds.add(report.getTargetId());
            } else if (ReportTargetTypeEnum.COMMENT.getValue().equals(report.getTargetType())) {
                commentIds.add(report.getTargetId());
            }
            if (report.getReporterId() != null) {
                userIds.add(report.getReporterId());
            }
            if (report.getHandleBy() != null) {
                userIds.add(report.getHandleBy());
            }
        }

        Map<Long, String> postTitles = new HashMap<>();
        if (!postIds.isEmpty()) {
            for (StudioPost post : studioPostMapper.selectByIds(postIds)) {
                postTitles.put(post.getId(), post.getTitle());
            }
        }
        Map<Long, String> commentSummaries = new HashMap<>();
        if (!commentIds.isEmpty()) {
            for (StudioPostComment comment : studioPostCommentMapper.selectByIds(commentIds)) {
                commentSummaries.put(comment.getId(), summarize(comment.getContent()));
            }
        }
        Map<Long, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser user : sysUserMapper.selectByIds(userIds)) {
                userNames.put(user.getId(), StrUtil.blankToDefault(user.getUserName(), user.getUserAccount()));
            }
        }

        List<ReportVO> records = new ArrayList<>(reports.size());
        for (StudioPostReport report : reports) {
            ReportVO vo = new ReportVO();
            vo.setId(report.getId());
            vo.setTargetType(report.getTargetType());
            ReportTargetTypeEnum targetType = ReportTargetTypeEnum.of(report.getTargetType());
            vo.setTargetTypeLabel(targetType == null ? report.getTargetType() : targetType.getDesc());
            vo.setTargetId(report.getTargetId());
            vo.setTargetTitle(ReportTargetTypeEnum.POST.getValue().equals(report.getTargetType())
                    ? postTitles.get(report.getTargetId())
                    : commentSummaries.get(report.getTargetId()));
            vo.setReporterId(report.getReporterId());
            vo.setReporterName(userNames.get(report.getReporterId()));
            vo.setReason(report.getReason());
            vo.setStatus(report.getStatus());
            ReportStatusEnum status = ReportStatusEnum.of(report.getStatus());
            vo.setStatusLabel(status == null ? String.valueOf(report.getStatus()) : status.getDesc());
            vo.setHandleBy(report.getHandleBy());
            vo.setHandlerName(userNames.get(report.getHandleBy()));
            vo.setHandleAt(report.getHandleAt());
            vo.setHandleResult(report.getHandleResult());
            vo.setCreatedAt(report.getCreatedAt());
            records.add(vo);
        }
        return records;
    }

    /** 读取目标信息（标题 + 所属帖子 ID） */
    private TargetInfo loadTargetInfo(ReportTargetTypeEnum targetType, long targetId) {
        if (targetType == ReportTargetTypeEnum.POST) {
            StudioPost post = studioPostMapper.selectById(targetId);
            return new TargetInfo(targetId, post == null ? null : post.getTitle(), targetId,
                    post == null ? null : post.getStatus());
        }
        StudioPostComment comment = studioPostCommentMapper.selectById(targetId);
        return new TargetInfo(targetId, comment == null ? null : summarize(comment.getContent()),
                comment == null ? null : comment.getPostId(),
                comment == null ? null : comment.getStatus());
    }

    /** 评论正文摘要（列表展示用） */
    private String summarize(String content) {
        if (StrUtil.isBlank(content)) {
            return "（空评论）";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= COMMENT_SUMMARY_LENGTH
                ? flat
                : flat.substring(0, COMMENT_SUMMARY_LENGTH) + "…";
    }

    /** 举报目标信息 */
    private record TargetInfo(long id, String title, Long postId, Integer status) {
    }
}

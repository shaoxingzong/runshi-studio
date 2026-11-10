package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.ContentAuditManager;
import com.bhu.runshistudioweb.manager.IdempotencyManager;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.StudioPostReportMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.AuditResult;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.enums.ReportStatusEnum;
import com.bhu.runshistudioweb.model.enums.ReportTargetTypeEnum;
import com.bhu.runshistudioweb.model.vo.post.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.post.PostCommentVO;
import com.bhu.runshistudioweb.service.NotificationService;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.PostCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子评论服务实现（楼中楼，先审后发）
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostCommentServiceImpl extends ServiceImpl<StudioPostCommentMapper, StudioPostComment>
        implements PostCommentService {

    /**
     * 审核动作：通过
     *
     * 取值必须与前端约定完全一致（大小写敏感）：审核动作是接口契约的一部分。
     */
    private static final String ACTION_APPROVE = "approve";

    /** 审核动作：驳回（驳回必须带理由，理由要给作者看） */
    private static final String ACTION_REJECT = "reject";

    /** 分页每页条数的默认值与上限 */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 回复链最大层级（顶层评论算第 1 层）：展示是扁平的，上限只用于挡住恶意的无限嵌套 */
    private static final int MAX_REPLY_DEPTH = 10;

    /** 帖子表 Mapper */
    private final StudioPostMapper studioPostMapper;

    /** 成员档案 Mapper */
    private final StudioMemberMapper studioMemberMapper;

    /** 内容审核（AI 三分法） */
    private final ContentAuditManager contentAuditManager;

    /** 站内通知（评论通过后告知帖子作者与被回复者） */
    private final NotificationService notificationService;

    /** 账号表：评论者展示名的兜底来源（非在队成员也能评论） */
    private final SysUserMapper sysUserMapper;

    /** 操作日志（审核与删除都要留痕） */
    private final OperationLogService operationLogService;

    /** 举报表 Mapper */
    private final StudioPostReportMapper studioPostReportMapper;

    /** 创建型接口的请求幂等令牌（评论）；Redis 不可用时自动放行 */
    private final IdempotencyManager idempotencyManager;

    /** 发表评论，或回复某条顶层评论（先审后发） */
    @Override
    public long addComment(PostCommentAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "评论内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();

        StudioPost post = studioPostMapper.selectById(request.getPostId());
        ThrowUtils.throwIf(post == null
                        || post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或尚未通过审核");

        // 正文 trim 后入库：首尾空格在楼层里看不出，却会占掉 1000 字的额度
        String content = StrUtil.trim(request.getContent());

        Long parentId = request.getParentId();
        if (parentId != null) {
            StudioPostComment parent = this.getById(parentId);
            // parentId 记录「直接回复谁」：同一帖子内任意一层评论都能被回复，展示时再拍平到顶层楼层
            ThrowUtils.throwIf(parent == null || !parent.getPostId().equals(request.getPostId()),
                    ErrorCode.PARAMS_ERROR, "只能回复同一帖子下的评论");
            // 层级上限只防恶意链式嵌套；正常对话几乎不会碰到
            ThrowUtils.throwIf(replyDepth(parent) >= MAX_REPLY_DEPTH,
                    ErrorCode.PARAMS_ERROR, "回复层级过深，请直接回复该评论所在楼层");
        }

        // 校验通过后才消费令牌：参数错误的请求不占用窗口，重复请求也不会重复触发 AI 审核
        idempotencyManager.assertFirstRequest(userId, "comment", request.getRequestId());

        // 组装实体：状态固定写待审，不读请求体里的任何状态字段（先审后发）
        StudioPostComment comment = new StudioPostComment();
        comment.setPostId(request.getPostId());
        comment.setAuthorId(userId);
        comment.setContent(content);
        comment.setParentId(parentId);
        comment.setStatus(AuditStatusEnum.PENDING.getValue());
        // 只有顶层才有楼层号；回复依附于父评论，编号会破坏"第几楼"的语义
        comment.setFloor(parentId == null ? nextFloor(request.getPostId()) : null);

        // 落库。save 只返回 boolean，失败时给 B0001（系统异常），而不是静默返回 0
        boolean saved = this.save(comment);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "评论失败，数据库异常");

        log.info("评论已提交待审 | commentId={} | postId={} | authorId={}",
                comment.getId(), request.getPostId(), userId);

        long newCommentId = comment.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(newCommentId, result));
        return comment.getId();
    }

    /** 把 AI 的结论写回评论（三分法处置） */
    private void applyAiVerdict(long commentId, AuditResult result) {
        StudioPostComment comment = this.getById(commentId);
        if (comment == null) {
            return;
        }

        AiAuditStatusEnum aiStatus = result.status();
        LocalDateTime now = LocalDateTime.now();

        // 两个 eq 条件缺一不可：「待审」保证人工结论优先，「未判」保证幂等
        LambdaUpdateWrapper<StudioPostComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPostComment::getId, commentId)
                // 只动待审的：管理员已处置过的内容，机器结论不能反悔
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                // 只动未判的：重复送审不会用新结论覆盖旧结论
                .eq(StudioPostComment::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                .set(StudioPostComment::getAiAuditStatus, aiStatus.getValue())
                .set(StudioPostComment::getAiReason, result.reason())
                .set(StudioPostComment::getAiAt, now);

        if (aiStatus == AiAuditStatusEnum.SAFE) {
            wrapper.set(StudioPostComment::getStatus, AuditStatusEnum.APPROVED.getValue())
                    .set(StudioPostComment::getAuditAt, now);
        } else if (aiStatus == AiAuditStatusEnum.BLOCKED) {
            wrapper.set(StudioPostComment::getStatus, AuditStatusEnum.REJECTED.getValue())
                    // 驳回理由给用户看：写 AI 的理由，作者才知道该改哪里
                    .set(StudioPostComment::getRejectReason, result.reason())
                    .set(StudioPostComment::getAuditAt, now);
        }
        // 灰色与判定失败：保持待审，只记录 AI 的建议

        // 用 baseMapper.update 拿影响行数：0 行不是错误，而是「已经被处理过」
        int rows = baseMapper.update(null, wrapper);
        if (rows == 0) {
            log.debug("AI 结论未落库（已被人工处置或已判过）| commentId={} | 结论={}",
                    commentId, aiStatus.getDesc());
            return;
        }

        // 只有「判安全」才真正公开，此时才维护评论数与发通知
        if (aiStatus == AiAuditStatusEnum.SAFE) {
            studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                    .eq(StudioPost::getId, comment.getPostId())
                    .setSql("comment_count = comment_count + 1"));
            notifyApprovedComments(List.of(comment));
        }
        log.info("AI 审核完成 | commentId={} | 结论={}", commentId, aiStatus.getDesc());
    }

    /**
     * 评论审核通过后投递互动通知（AI 判安全与人工审核通过两条路径共用）
     *
     * <p>一条评论只打扰一个目标：
     * 顶层评论通知帖子作者（POST_COMMENTED）；层内互相回复只通知被 @ 的那个人（COMMENT_REPLIED），
     * 不再额外通知帖子作者——否则楼层里两个人对话会把楼主反复吵醒。
     * 「不给自己发」由 NotificationService.notify 内置，这里不用重复判断。
     *
     * <p>批量审核场景下，帖子、评论者展示名、父评论作者都一次查完（3 次查询封顶），
     * 避免逐条评论各查一次库的 N+1。
     */
    private void notifyApprovedComments(List<StudioPostComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }

        // ① 帖子（作者 ID + 标题）
        Set<Long> postIds = comments.stream()
                .map(StudioPostComment::getPostId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StudioPost> posts = postIds.isEmpty() ? Map.of()
                : studioPostMapper.selectList(new LambdaQueryWrapper<StudioPost>()
                        .in(StudioPost::getId, postIds)
                        .select(StudioPost::getId, StudioPost::getAuthorId, StudioPost::getTitle))
                        .stream().collect(Collectors.toMap(StudioPost::getId, p -> p, (a, b) -> a));

        // ② 评论者展示名：先按在队成员档案取，查不到的用账号昵称兜底
        Set<Long> actorIds = comments.stream()
                .map(StudioPostComment::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> actorNames = displayNames(actorIds);

        // ③ 父评论作者（仅回复场景需要）
        Set<Long> parentIds = comments.stream()
                .map(StudioPostComment::getParentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> parentAuthors = parentIds.isEmpty() ? Map.of()
                : this.list(new LambdaQueryWrapper<StudioPostComment>()
                        .in(StudioPostComment::getId, parentIds)
                        .select(StudioPostComment::getId, StudioPostComment::getAuthorId))
                        .stream().collect(Collectors.toMap(
                                StudioPostComment::getId, StudioPostComment::getAuthorId, (a, b) -> a));

        for (StudioPostComment comment : comments) {
            StudioPost post = posts.get(comment.getPostId());
            String postTitle = post == null || StrUtil.isBlank(post.getTitle())
                    ? "原帖子" : post.getTitle();
            String actorName = actorNames.getOrDefault(comment.getAuthorId(), "有人");
            String summary = commentSummary(comment.getContent());

            // ① 层内回复：只通知被 @ 的人，文案带上 @ 姓名；不额外打扰帖子作者。
            //    bizId 用评论 ID 而不是帖子 ID：同一楼层里多次被 @ 时每条都能独立留下，
            //    否则会被 uk_receiver_biz 唯一键吞掉，只剩第一条（「互相 @ 漏通知」的根因）。
            Long parentAuthorId = comment.getParentId() == null
                    ? null : parentAuthors.get(comment.getParentId());
            if (comment.getParentId() != null) {
                if (parentAuthorId != null) {
                    notificationService.notify(parentAuthorId, comment.getAuthorId(),
                            NotificationTypeEnum.COMMENT_REPLIED.getValue(), "COMMENT", comment.getId(),
                            actorName + " 在回复中 @ 了你",
                            "《" + postTitle + "》：" + summary);
                }
                continue;
            }

            // ② 顶层评论：逐条通知帖子作者。bizId 用评论 ID，同一帖子多条评论不会被唯一键吞掉
            if (post != null && post.getAuthorId() != null) {
                notificationService.notify(post.getAuthorId(), comment.getAuthorId(),
                        NotificationTypeEnum.POST_COMMENTED.getValue(), "COMMENT", comment.getId(),
                        actorName + " 评论了你的帖子",
                        "《" + postTitle + "》：" + summary);
            }
        }
    }

    /** 批量取评论者展示名：在队成员用档案姓名，查不到的用账号昵称（一次查询，避免 N+1） */
    private Map<Long, String> displayNames(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        authorProfiles(new ArrayList<>(userIds))
                .forEach((userId, profile) -> names.put(userId, profile.name()));

        List<Long> missing = userIds.stream()
                .filter(id -> !names.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .in(SysUser::getId, missing)
                            .select(SysUser::getId, SysUser::getUserName, SysUser::getUserAccount))
                    .forEach(user -> {
                        String display = StrUtil.isNotBlank(user.getUserName())
                                ? user.getUserName() : user.getUserAccount();
                        if (StrUtil.isNotBlank(display)) {
                            names.put(user.getId(), display);
                        }
                    });
        }
        return names;
    }

    /** 某帖子的公开评论列表（游客可调用，楼中楼已组装好） */
    @Override
    public List<PostCommentVO> listApprovedByPost(long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        // 一次查完：某帖的全部已通过评论，按 id 升序让结果天然按时间有序（雪花 ID 递增）
        LambdaQueryWrapper<StudioPostComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostComment::getPostId, postId)
                // 只查已通过：待审与已驳回的评论对公众等同于不存在
                .eq(StudioPostComment::getStatus, AuditStatusEnum.APPROVED.getValue())
                .orderByAsc(StudioPostComment::getId);
        List<StudioPostComment> all = this.list(wrapper);
        if (all.isEmpty()) {
            // 空集合提前返回：省掉后面的取名查询，也避免用空集合去拼 IN 条件
            return List.of();
        }

        // 作者信息（姓名 + 头像）一次查完（批量，避免逐条查的 N+1）
        Map<Long, AuthorProfile> profiles = authorProfiles(all.stream()
                .map(StudioPostComment::getAuthorId).toList());

        // 被回复者姓名：parentId 指向的是某条评论（可能是顶层、也可能是层内另一条回复）。
        // 一次 IN 查出父评论作者，再批量取名，避免渲染 @ 时逐条反查。
        Set<Long> parentIds = all.stream()
                .map(StudioPostComment::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Long> parentAuthorIds = parentIds.isEmpty() ? Map.of()
                : this.list(new LambdaQueryWrapper<StudioPostComment>()
                        .in(StudioPostComment::getId, parentIds)
                        .select(StudioPostComment::getId, StudioPostComment::getAuthorId))
                .stream().collect(Collectors.toMap(
                        StudioPostComment::getId, StudioPostComment::getAuthorId, (a, b) -> a));
        // 父评论作者可能也是「非在队成员」：displayNames 内部先查成员档案、再拿账号昵称兜底
        Map<Long, String> parentNames = displayNames(new HashSet<>(parentAuthorIds.values()));

        List<PostCommentVO> tops = new ArrayList<>();
        Map<Long, List<PostCommentVO>> childrenByParent = new HashMap<>();
        for (StudioPostComment comment : all) {
            PostCommentVO vo = new PostCommentVO();
            BeanUtils.copyProperties(comment, vo);
            AuthorProfile author = profiles.get(comment.getAuthorId());
            if (author != null) {
                vo.setAuthorName(author.name());
                vo.setAuthorAvatar(author.avatar());
            }
            if (comment.getParentId() != null) {
                vo.setReplyToId(comment.getParentId());
                Long parentAuthorId = parentAuthorIds.get(comment.getParentId());
                if (parentAuthorId != null) {
                    vo.setReplyToName(parentNames.get(parentAuthorId));
                }
                childrenByParent.computeIfAbsent(comment.getParentId(), key -> new ArrayList<>()).add(vo);
            } else {
                tops.add(vo);
            }
        }
        // 「真存储、扁显示」：每个顶层评论下收集全部后代（不限层级），按 id 升序拍平到 children。
        // 父评论未通过审核时，子评论不可达（与之前一样不会出现在公开列表里）。
        for (PostCommentVO top : tops) {
            top.getChildren().addAll(flattenDescendants(top.getId(), childrenByParent));
        }
        return tops;
    }

    /** 待审评论队列（管理员视角，先进先出） */
    @Override
    public Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize) {
        long safeCurrent = current < 1 ? 1 : current;
        long safeSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        safeSize = Math.min(safeSize, MAX_PAGE_SIZE);

        // 只查待审、按创建时间升序（先进先出）
        LambdaQueryWrapper<StudioPostComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPostComment::getCreatedAt);

        Page<StudioPostComment> entityPage = this.page(new Page<>(safeCurrent, safeSize), wrapper);
        // 作者信息（姓名 + 头像）一次查完（批量），不逐条查
        Map<Long, AuthorProfile> profiles = authorProfiles(entityPage.getRecords().stream()
                .map(StudioPostComment::getAuthorId).toList());

        Page<PostCommentAuditVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(comment -> {
                    PostCommentAuditVO vo = new PostCommentAuditVO();
                    BeanUtils.copyProperties(comment, vo);
                    AuthorProfile author = profiles.get(comment.getAuthorId());
                    if (author != null) {
                        vo.setAuthorName(author.name());
                    }
                    return vo;
                })
                .toList());
        return voPage;
    }

    /** 批量审核评论（通过 / 驳回），并在通过时维护帖子评论数、通知被回复者 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int audit(AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "审核参数不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long auditorId = StpUtil.getLoginIdAsLong();

        String action = StrUtil.trim(request.getAction());
        ThrowUtils.throwIf(!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action),
                ErrorCode.PARAMS_ERROR, "审核动作只能是 " + ACTION_APPROVE + " 或 " + ACTION_REJECT);

        boolean approved = ACTION_APPROVE.equals(action);
        // 驳回必须给理由：它是给作者看的，没有理由等于让作者自己猜哪里不合适
        ThrowUtils.throwIf(!approved && StrUtil.isBlank(request.getRejectReason()),
                ErrorCode.PARAMS_ERROR, "驳回时必须填写理由");

        List<StudioPostComment> targets = this.list(new LambdaQueryWrapper<StudioPostComment>()
                .in(StudioPostComment::getId, request.getIds())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue()));
        if (targets.isEmpty()) {
            // 一条待审的都没有：直接返回 0，不必再发 UPDATE（也没有通知该发）
            return 0;
        }

        LambdaUpdateWrapper<StudioPostComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(StudioPostComment::getId, targets.stream().map(StudioPostComment::getId).toList())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .set(StudioPostComment::getStatus, approved
                        ? AuditStatusEnum.APPROVED.getValue()
                        : AuditStatusEnum.REJECTED.getValue())
                // 审核人写真人 ID：这是「人放的」与「机器放的」唯一的区分依据
                .set(StudioPostComment::getAuditBy, auditorId)
                .set(StudioPostComment::getAuditAt, LocalDateTime.now())
                .set(StudioPostComment::getUpdatedBy, auditorId)
                .set(StudioPostComment::getUpdatedAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPostComment::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }
        // this.update 只返回 boolean，这里要的是影响行数，故走 baseMapper.update
        int rows = baseMapper.update(null, wrapper);

        if (approved) {
            Map<Long, Long> incrementByPost = targets.stream()
                    .collect(Collectors.groupingBy(StudioPostComment::getPostId, Collectors.counting()));
            for (Map.Entry<Long, Long> entry : incrementByPost.entrySet()) {
                // 拼接的是内部统计出来的数字，不是用户输入；用 SQL 表达式避免读改写竞态
                studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, entry.getKey())
                        .setSql("comment_count = comment_count + " + entry.getValue()));
            }
            // 一次批量投递：AI 灰色转人工通过后也不会漏发，且不会逐条查库
            notifyApprovedComments(targets);
        }

        // 留痕：一条评论一行（按内容反查「这句是谁放出来的」）；rows 为 0 时不记
        if (rows > 0) {
            String detail = approved
                    ? "评论审核通过"
                    : "驳回理由：" + StrUtil.trim(request.getRejectReason());
            operationLogService.recordBatch(
                    approved ? OperationTypeEnum.COMMENT_APPROVE : OperationTypeEnum.COMMENT_REJECT,
                    targets.stream()
                            .map(target -> new OperationLogService.LogTarget(
                                    target.getId(), commentSummary(target.getContent())))
                            .toList(),
                    detail);
        }
        return rows;
    }

    /** 删除一条评论，连带删掉它下面的回复（逻辑删除） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteComment(long id) {
        // 删评论是管理动作，必须先有登录态：不知道操作人就无法留痕
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "评论 id 不合法");

        StudioPostComment target = this.getById(id);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "评论不存在");
        ThrowUtils.throwIf(target.getStatus() == null
                        || target.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.PARAMS_ERROR, "只能删除已通过的评论；待审内容请使用驳回");

        long postId = target.getPostId();

        // 一次查全帖的 id/parentId，在内存里迭代收集全部后代：链式回复（A 回 B、B 回 C）也要一起删。
        // 用 visited 防脏数据成环，用迭代而非递归避免栈溢出。
        List<Long> removingIds = collectDescendantIds(postId, id);

        this.update(new LambdaUpdateWrapper<StudioPostComment>()
                .in(StudioPostComment::getId, removingIds)
                .set(StudioPostComment::getDeletedBy, operatorId));

        int rows = baseMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId)
                .in(StudioPostComment::getId, removingIds));

        // 同事务关闭被删评论上的待处理举报（含被连带删除的回复）
        closePendingReports(removingIds, operatorId);

        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .setSql("comment_count = GREATEST(comment_count - " + rows + ", 0)"));

        // 删除评论同样要留痕：删的是已公开内容，作者与读者都会发现「它不见了」
        operationLogService.record(OperationTypeEnum.COMMENT_DELETE, id, commentSummary(target.getContent()),
                "连带删除回复 " + Math.max(rows - 1, 0) + " 条（共 " + rows + " 条）；所属帖子 ID：" + postId);
        return rows;
    }

    // 私有工具

    /**
     * 收集某个顶层评论下的全部后代（含多层），按 id 升序返回
     *
     * <p>用迭代而不是递归：即使数据被脏写成环，也不会栈溢出；visited 保证每个节点只收一次。
     */
    private List<PostCommentVO> flattenDescendants(
            Long rootId, Map<Long, List<PostCommentVO>> childrenByParent) {
        List<PostCommentVO> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long parentId = stack.pop();
            for (PostCommentVO child : childrenByParent.getOrDefault(parentId, List.of())) {
                // 同一节点只收一次：正常数据不会重复，但不能让脏数据把接口拖死
                if (!visited.add(child.getId())) {
                    continue;
                }
                result.add(child);
                stack.push(child.getId());
            }
        }
        // 雪花 ID 单调递增：按 id 排序等价于按发布时间排序，深链里的回复也落在对话发生的顺序上
        result.sort(Comparator.comparing(PostCommentVO::getId));
        return result;
    }

    /**
     * 收集某条评论及其全部后代的 ID（含自身），用于删除
     *
     * <p>一次查出该帖所有评论的 id/parentId，在内存里按父子关系迭代展开；
     * visited 防环、迭代防栈溢出，也不需要一层一层回表查。
     */
    private List<Long> collectDescendantIds(long postId, long rootId) {
        List<StudioPostComment> all = this.list(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId)
                .select(StudioPostComment::getId, StudioPostComment::getParentId));

        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (StudioPostComment comment : all) {
            if (comment.getParentId() != null) {
                childrenByParent.computeIfAbsent(comment.getParentId(), key -> new ArrayList<>())
                        .add(comment.getId());
            }
        }

        Set<Long> visited = new HashSet<>();
        List<Long> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        visited.add(rootId);
        result.add(rootId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            for (Long child : childrenByParent.getOrDefault(current, List.of())) {
                if (visited.add(child)) {
                    result.add(child);
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /** 计算一条评论的层级（顶层=1）；父链异常过长时最多走到上限即停 */
    private int replyDepth(StudioPostComment comment) {
        int depth = 1;
        Long parentId = comment.getParentId();
        while (parentId != null && depth < MAX_REPLY_DEPTH) {
            depth++;
            StudioPostComment parent = this.getById(parentId);
            if (parent == null) {
                break;
            }
            parentId = parent.getParentId();
        }
        return depth;
    }

    /** 关闭被删评论上的待处理举报 */
    private void closePendingReports(List<Long> targetIds, long operatorId) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        studioPostReportMapper.update(null, new LambdaUpdateWrapper<StudioPostReport>()
                .eq(StudioPostReport::getTargetType, ReportTargetTypeEnum.COMMENT.getValue())
                .in(StudioPostReport::getTargetId, targetIds)
                .eq(StudioPostReport::getStatus, ReportStatusEnum.PENDING.getValue())
                .set(StudioPostReport::getStatus, ReportStatusEnum.RESOLVED.getValue())
                .set(StudioPostReport::getHandleBy, operatorId)
                .set(StudioPostReport::getHandleAt, now)
                .set(StudioPostReport::getHandleResult, "内容已被删除")
                .set(StudioPostReport::getUpdatedBy, operatorId)
                .set(StudioPostReport::getUpdatedAt, now));
    }

    /** 评论正文摘要（只用于日志的 target_title） */
    private String commentSummary(String content) {
        if (StrUtil.isBlank(content)) {
            return "（空评论）";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 取下一个楼层号：当前帖子最大楼层 + 1（只给顶层评论用） */
    private int nextFloor(Long postId) {
        // 拼一条聚合查询，只取 max(floor) 一列，不把评论行读回来
        QueryWrapper<StudioPostComment> wrapper = new QueryWrapper<>();
        // 只取聚合列：不把整行评论读回内存，这个查询只为了拿一个数字
        wrapper.select("MAX(floor) AS max_floor").eq("post_id", postId);
        // 本版本的 getObj 需要一并传入转换函数；聚合结果可能是 BigDecimal，统一按 Number 处理
        Object result = this.getObj(wrapper, value -> value);
        // 该帖还没有顶层评论时聚合结果是 null，按 0 处理，加一得到第 1 楼
        int maxFloor = result instanceof Number number ? number.intValue() : 0;
        return maxFloor + 1;
    }

    /** 批量查评论人姓名（一次查询，避免 N+1） */
    private Map<Long, AuthorProfile> authorProfiles(List<Long> userIds) {
        // 空集合直接返回空映射——拿空集合拼 IN () 是语法错误，MySQL 会直接报错
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // 按 user_id IN 且 member_status = 在队，一次查出成员档案
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getUserId, userIds)
                // 只认在队成员：离队成员的姓名不再出现在公开评论列表上
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        List<StudioMember> members = studioMemberMapper.selectList(wrapper);

        // 在内存里拼成 userId → 档案 的映射，跳过姓名为空与未绑定账号的档案
        Map<Long, AuthorProfile> profiles = new HashMap<>();
        for (StudioMember member : members) {
            // 未绑定账号的档案 user_id 为空，姓名也可能没填——两者都要跳过，否则映射里会出现 null 键
            if (member.getUserId() != null && StrUtil.isNotBlank(member.getName())) {
                profiles.put(member.getUserId(),
                        new AuthorProfile(member.getName(), StrUtil.trimToNull(member.getAvatar())));
            }
        }
        return profiles;
    }

    /** 评论人的展示信息（姓名 + 头像） */
    private record AuthorProfile(String name, String avatar) {
    }
}

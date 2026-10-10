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
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.AuditResult;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.vo.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.PostCommentVO;
import com.bhu.runshistudioweb.service.PostCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 帖子评论服务实现（楼中楼）
 *
 * author: shaoshing
 *
 * <p><b>列表组装是这个类最需要理解的地方</b>：
 * {@code WHERE post_id = ? AND status = 1} 一次把整个帖子的评论查回来，
 * 再在内存里按 {@code parentId} 挂成树。
 * <b>绝不能</b>写成「先查顶层，再对每条顶层查它的回复」——那是标准 N+1，
 * 一个热门帖子有 50 条顶层评论就是 50 次查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostCommentServiceImpl extends ServiceImpl<StudioPostCommentMapper, StudioPostComment>
        implements PostCommentService {

    /** 审核动作：通过 */
    private static final String ACTION_APPROVE = "approve";

    /** 审核动作：驳回 */
    private static final String ACTION_REJECT = "reject";

    /** 分页每页条数的默认值与上限 */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    private final StudioPostMapper studioPostMapper;

    private final StudioMemberMapper studioMemberMapper;

    /**
     * 内容审核（AI 三分法）
     *
     * <p>与帖子共用同一个 Manager：判定逻辑不区分内容类型，
     * 差别只在「结论落到哪张表、要不要顺带维护帖子的评论数」，那是本类的事。
     */
    private final ContentAuditManager contentAuditManager;

    @Override
    public long addComment(PostCommentAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "评论内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();

        // ① 帖子必须存在且已通过：对待审/已驳回的帖子评论没有意义，也避免绕过审核往未公开内容上挂评论
        StudioPost post = studioPostMapper.selectById(request.getPostId());
        // 注意：getValue() 返回的是 int，不能对它调 equals；且 status 理论上可能为 null，要先判空
        ThrowUtils.throwIf(post == null
                        || post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或尚未通过审核");

        String content = StrUtil.trim(request.getContent());

        // ② 回复的合法性：父评论必须是同一帖子下的**顶层**评论（只支持两层楼中楼）
        Long parentId = request.getParentId();
        if (parentId != null) {
            StudioPostComment parent = this.getById(parentId);
            ThrowUtils.throwIf(parent == null
                            || !parent.getPostId().equals(request.getPostId())
                            || parent.getParentId() != null,
                    ErrorCode.PARAMS_ERROR, "只能回复帖子下的顶层评论");
        }

        StudioPostComment comment = new StudioPostComment();
        comment.setPostId(request.getPostId());
        comment.setAuthorId(userId);
        comment.setContent(content);
        comment.setParentId(parentId);
        comment.setStatus(AuditStatusEnum.PENDING.getValue());
        // 只有顶层才有楼层号；回复依附于父评论，编号会破坏"第几楼"的语义
        comment.setFloor(parentId == null ? nextFloor(request.getPostId()) : null);

        boolean saved = this.save(comment);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "评论失败，数据库异常");

        // 注意：这里**不**累加 post.comment_count。
        // 评论是待审状态，此时计数会让用户看到"有 1 条评论"却点开什么都没有；
        // 计数只在「真正公开」这一刻累加——人工通过走审核接口，AI 判安全走 applyAiVerdict。
        log.info("评论已提交待审 | commentId={} | postId={} | authorId={}",
                comment.getId(), request.getPostId(), userId);

        // 异步送 AI 初判（不阻塞提交）。闭包里只带 commentId 与正文：
        // 回调跑在虚拟线程上，那里取不到 RequestContextHolder
        long newCommentId = comment.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(newCommentId, result));
        return comment.getId();
    }

    /**
     * 把 AI 的结论写回评论（三分法处置）
     *
     * <p>与帖子那一版是同一套规则：<b>只更新「待审且未判」的记录</b>，
     * 既保证幂等（重复送审不覆盖已有结论），
     * 也保证管理员的手动处置不会被后到的机器结论改写。
     *
     * @param commentId 评论 ID
     * @param result    AI 结论
     */
    private void applyAiVerdict(long commentId, AuditResult result) {
        // 先取评论：通过后要拿 postId 去维护帖子的评论数，这是 update 拿不到的
        StudioPostComment comment = this.getById(commentId);
        if (comment == null) {
            return;
        }

        AiAuditStatusEnum aiStatus = result.status();
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<StudioPostComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPostComment::getId, commentId)
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .eq(StudioPostComment::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                .set(StudioPostComment::getAiAuditStatus, aiStatus.getValue())
                .set(StudioPostComment::getAiReason, result.reason())
                .set(StudioPostComment::getAiAt, now);

        if (aiStatus == AiAuditStatusEnum.SAFE) {
            wrapper.set(StudioPostComment::getStatus, AuditStatusEnum.APPROVED.getValue())
                    .set(StudioPostComment::getAuditAt, now);
        } else if (aiStatus == AiAuditStatusEnum.BLOCKED) {
            wrapper.set(StudioPostComment::getStatus, AuditStatusEnum.REJECTED.getValue())
                    .set(StudioPostComment::getRejectReason, result.reason())
                    .set(StudioPostComment::getAuditAt, now);
        }
        // 灰色与判定失败：保持待审，只记录 AI 的建议

        int rows = baseMapper.update(null, wrapper);
        if (rows == 0) {
            log.debug("AI 结论未落库（已被人工处置或已判过）| commentId={} | 结论={}",
                    commentId, aiStatus.getDesc());
            return;
        }

        if (aiStatus == AiAuditStatusEnum.SAFE) {
            // 与人工通过走同一套增量维护：评论数在「真正公开」这一刻才 +1，
            // 这样「评论数」与「点开能看到的评论」始终一致。
            // 拼接的是固定数字 1，不是用户输入；用 SQL 表达式避免读改写竞态
            studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                    .eq(StudioPost::getId, comment.getPostId())
                    .setSql("comment_count = comment_count + 1"));
        }
        log.info("AI 审核完成 | commentId={} | 结论={}", commentId, aiStatus.getDesc());
    }

    @Override
    public List<PostCommentVO> listApprovedByPost(long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        // ① 一次查完：某帖的全部已通过评论，第三列 id 让结果天然按时间有序（雪花 ID 递增）
        LambdaQueryWrapper<StudioPostComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostComment::getPostId, postId)
                .eq(StudioPostComment::getStatus, AuditStatusEnum.APPROVED.getValue())
                .orderByAsc(StudioPostComment::getId);
        List<StudioPostComment> all = this.list(wrapper);
        if (all.isEmpty()) {
            return List.of();
        }

        // ② 作者名一次查完（批量，避免逐条查的 N+1）
        Map<Long, String> names = authorNames(all.stream()
                .map(StudioPostComment::getAuthorId).toList());

        // ③ 内存挂树：顶层单独收集，回复按 parentId 暂存
        List<PostCommentVO> tops = new ArrayList<>();
        Map<Long, List<PostCommentVO>> repliesByParent = new HashMap<>();
        for (StudioPostComment comment : all) {
            PostCommentVO vo = new PostCommentVO();
            BeanUtils.copyProperties(comment, vo);
            vo.setAuthorName(names.get(comment.getAuthorId()));
            if (comment.getParentId() == null) {
                tops.add(vo);
            } else {
                repliesByParent.computeIfAbsent(comment.getParentId(), key -> new ArrayList<>()).add(vo);
            }
        }
        // 挂上各自的回复；没有回复的保持空列表（VO 里已初始化，前端可直接 v-for）
        for (PostCommentVO top : tops) {
            top.setChildren(repliesByParent.getOrDefault(top.getId(), List.of()));
        }
        return tops;
    }

    @Override
    public Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize) {
        long safeCurrent = current < 1 ? 1 : current;
        long safeSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        safeSize = Math.min(safeSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<StudioPostComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPostComment::getCreatedAt);

        Page<StudioPostComment> entityPage = this.page(new Page<>(safeCurrent, safeSize), wrapper);
        Map<Long, String> names = authorNames(entityPage.getRecords().stream()
                .map(StudioPostComment::getAuthorId).toList());

        Page<PostCommentAuditVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream()
                .map(comment -> {
                    PostCommentAuditVO vo = new PostCommentAuditVO();
                    BeanUtils.copyProperties(comment, vo);
                    vo.setAuthorName(names.get(comment.getAuthorId()));
                    return vo;
                })
                .toList());
        return voPage;
    }

    @Override
    public int audit(AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "审核参数不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long auditorId = StpUtil.getLoginIdAsLong();

        String action = StrUtil.trim(request.getAction());
        ThrowUtils.throwIf(!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action),
                ErrorCode.PARAMS_ERROR, "审核动作只能是 " + ACTION_APPROVE + " 或 " + ACTION_REJECT);

        boolean approved = ACTION_APPROVE.equals(action);
        ThrowUtils.throwIf(!approved && StrUtil.isBlank(request.getRejectReason()),
                ErrorCode.PARAMS_ERROR, "驳回时必须填写理由");

        // 先取出这一批里真正处于「待审」的评论：
        // ① 保证幂等（已处理的不重复动）；② 通过后要按帖子统计各自的增量
        List<StudioPostComment> targets = this.list(new LambdaQueryWrapper<StudioPostComment>()
                .in(StudioPostComment::getId, request.getIds())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue()));
        if (targets.isEmpty()) {
            return 0;
        }

        LambdaUpdateWrapper<StudioPostComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(StudioPostComment::getId, targets.stream().map(StudioPostComment::getId).toList())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .set(StudioPostComment::getStatus, approved
                        ? AuditStatusEnum.APPROVED.getValue()
                        : AuditStatusEnum.REJECTED.getValue())
                .set(StudioPostComment::getAuditBy, auditorId)
                .set(StudioPostComment::getAuditAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPostComment::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }
        // 同帖子审核：this.update 只返回 boolean，这里要的是影响行数
        int rows = baseMapper.update(null, wrapper);

        if (approved) {
            // 评论数在「真正公开」这一刻才累加：发布时刻意不计数（那时还是待审），
            // 这样「评论数」与「看得见的评论」始终一致，不会出现"显示 1 条却点开没有"
            Map<Long, Long> incrementByPost = targets.stream()
                    .collect(Collectors.groupingBy(StudioPostComment::getPostId, Collectors.counting()));
            for (Map.Entry<Long, Long> entry : incrementByPost.entrySet()) {
                // 拼接的是内部统计出来的数字，不是用户输入；用 SQL 表达式避免读改写竞态
                studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, entry.getKey())
                        .setSql("comment_count = comment_count + " + entry.getValue()));
            }
        }
        return rows;
    }

    @Override
    public int deleteComment(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "评论 id 不合法");

        StudioPostComment target = this.getById(id);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "评论不存在");
        // 只删「已通过」的：待审内容应当走驳回，让作者收到反馈；
        // 直接删掉等于什么都不告诉作者
        ThrowUtils.throwIf(target.getStatus() == null
                        || target.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.PARAMS_ERROR, "只能删除已通过的评论；待审内容请使用驳回");

        long postId = target.getPostId();

        // 审计：管理员删的是**别人的**内容，光有 deleted_at 只知道时间、不知道人，
        // 出争议时必须能查到操作人
        this.update(new LambdaUpdateWrapper<StudioPostComment>()
                .eq(StudioPostComment::getId, id)
                .set(StudioPostComment::getDeletedBy, operatorId));

        // 级联：顶层评论连同它的回复一并逻辑删除。
        // 只删顶层会留下 parentId 指向已删评论的孤儿回复——前端会渲染出没有上下文的内容
        // this.remove 只返回 boolean，这里要的是实际删除条数（含回复），故走 baseMapper
        int rows = baseMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId)
                .and(nested -> nested
                        .eq(StudioPostComment::getId, id)
                        .or()
                        .eq(StudioPostComment::getParentId, id)));

        // 评论数回退；GREATEST 兜底防止脏数据下减成负数
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .setSql("comment_count = GREATEST(comment_count - " + rows + ", 0)"));
        return rows;
    }

    // ==================== 私有工具 ====================

    /**
     * 取下一个楼层号：当前帖子最大楼层 + 1
     *
     * <p><b>并发下可能重复</b>：两个顶层评论同时提交，都会读到同一个最大值而算出相同楼层。
     * 这是刻意接受的——楼层号只是展示用的序号，重复不影响任何功能，
     * 而要为它加唯一索引 + 冲突重试，对当前量级是过度设计。
     * 若将来确实需要严格递增，再给 {@code (post_id, floor)} 加唯一索引并 catch 重试即可。
     */
    private int nextFloor(Long postId) {
        QueryWrapper<StudioPostComment> wrapper = new QueryWrapper<>();
        wrapper.select("MAX(floor) AS max_floor").eq("post_id", postId);
        // 本版本的 getObj 需要一并传入转换函数；聚合结果可能是 BigDecimal，统一按 Number 处理
        Object result = this.getObj(wrapper, value -> value);
        int maxFloor = result instanceof Number number ? number.intValue() : 0;
        return maxFloor + 1;
    }

    /**
     * 批量查评论人姓名（一次查询，避免 N+1）
     *
     * @param userIds 用户 ID 集合
     * @return userId → 成员姓名
     */
    private Map<Long, String> authorNames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getUserId, userIds)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        List<StudioMember> members = studioMemberMapper.selectList(wrapper);

        Map<Long, String> names = new HashMap<>();
        for (StudioMember member : members) {
            if (member.getUserId() != null && StrUtil.isNotBlank(member.getName())) {
                names.put(member.getUserId(), member.getName());
            }
        }
        return names;
    }
}

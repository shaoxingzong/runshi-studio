package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.config.PostProperties;
import com.bhu.runshistudioweb.manager.ContentAuditManager;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.mapper.StudioPostReportMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.AuditResult;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.dto.post.PostAdminQueryRequest;
import com.bhu.runshistudioweb.model.dto.post.PostUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.entity.StudioPostLike;
import com.bhu.runshistudioweb.model.entity.StudioPostReport;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.enums.ReportStatusEnum;
import com.bhu.runshistudioweb.model.enums.ReportTargetTypeEnum;
import com.bhu.runshistudioweb.model.vo.post.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.post.PostSelfDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostVO;
import com.bhu.runshistudioweb.model.vo.post.PostAdminDetailVO;
import com.bhu.runshistudioweb.service.NotificationService;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.PostService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import com.bhu.runshistudioweb.utils.ImageUrlWhitelist;
import com.bhu.runshistudioweb.utils.MarkdownSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 帖子服务实现：先审后发（AI 初判 + 人工审核）、公开列表、点赞、管理端维护
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<StudioPostMapper, StudioPost> implements PostService {

    /** 正文长度上限 */
    private static final int CONTENT_MAX_LENGTH = 50000;

    /** 摘要兜底长度：正文剥掉 Markdown 标记后取前这么多字 */
    private static final int SUMMARY_FALLBACK_LENGTH = 120;

    /** 分页每页条数的默认值与上限（上限用来防「一次拉全表」） */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /** 审核动作：通过 */
    private static final String ACTION_APPROVE = "approve";

    /** 审核动作：驳回 */
    private static final String ACTION_REJECT = "reject";

    /** Markdown 图片语法：![任意描述](地址) */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    private final StudioMemberService studioMemberService;

    private final StudioMemberMapper studioMemberMapper;

    /** 帖子配置：正文图片域名白名单等 */
    private final PostProperties postProperties;

    /**
     * 内容审核（AI 三分法）
     *
     * 只负责「判定」，落库由本类完成——依赖方向必须单向，否则循环依赖启动即失败。
     */
    private final ContentAuditManager contentAuditManager;

    /** 点赞关系（studio_post_like） */
    private final StudioPostLikeMapper studioPostLikeMapper;

    /** 评论（studio_post_comment） */
    private final StudioPostCommentMapper studioPostCommentMapper;

    /** 站内通知（审核结论送达作者） */
    private final NotificationService notificationService;

    /** 操作日志（审核 / 删除 / 置顶 / 代改都要留痕） */
    private final OperationLogService operationLogService;

    /** 举报表 Mapper */
    private final StudioPostReportMapper studioPostReportMapper;

    /** 用户（sys_user） */
    private final SysUserMapper sysUserMapper;

    /** 发布帖子（先审后发） */
    @Override
    public long addPost(PostAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "发帖内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        // 与考勤同一套判定：认 studio_member 表，不认角色标签
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以发帖");

        String content = StrUtil.trim(request.getContent());
        // 先用常数挡住超大输入，再谈内容清洗：清洗本身要遍历全文，不能让恶意大文本先跑一遍
        ThrowUtils.throwIf(content.length() > CONTENT_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "正文过长，请控制在 " + CONTENT_MAX_LENGTH + " 字以内");

        content = MarkdownSanitizer.sanitize(content);

        // 图片必须是站内路径：外链图会成为追踪像素（泄露访客 IP），也可能将来裂图
        assertImagesAreAllowed(content);

        StudioPost post = new StudioPost();
        post.setTitle(StrUtil.trim(request.getTitle()));
        post.setContent(content);
        // 先审后发：一律先落待审，仅作者在「我的发帖」里可见
        post.setStatus(AuditStatusEnum.PENDING.getValue());
        post.setAuthorId(userId);
        // 三个计数与置顶显式写 0：不依赖数据库默认值，返回给前端的对象立刻就是完整状态
        post.setViewCount(0);
        post.setCommentCount(0);
        post.setPinned(0);
        // 摘要与封面的兜底生成：不填也能有列表展示效果
        post.setSummary(StrUtil.isBlank(request.getSummary())
                ? buildSummary(content)
                : StrUtil.trim(request.getSummary()));
        post.setCoverImage(StrUtil.isBlank(request.getCoverImage())
                ? extractFirstImage(content)
                : StrUtil.trim(request.getCoverImage()));

        boolean saved = this.save(post);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "发帖失败，数据库异常");

        log.info("帖子已提交待审 | postId={} | authorId={}", post.getId(), userId);

        long newPostId = post.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(newPostId, result));
        return post.getId();
    }

    /** 把 AI 的结论写回帖子（三分法处置） */
    private void applyAiVerdict(long postId, AuditResult result) {
        AiAuditStatusEnum aiStatus = result.status();
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPost::getId, postId)
                // 只动待审的：管理员已处置过的内容，机器结论不能反悔
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                // 只动未判的：保证幂等，重复送审不会用新结论覆盖旧结论
                .eq(StudioPost::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                .set(StudioPost::getAiAuditStatus, aiStatus.getValue())
                .set(StudioPost::getAiReason, result.reason())
                .set(StudioPost::getAiAt, now);

        if (aiStatus == AiAuditStatusEnum.SAFE) {
            wrapper.set(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                    .set(StudioPost::getAuditAt, now);
        } else if (aiStatus == AiAuditStatusEnum.BLOCKED) {
            wrapper.set(StudioPost::getStatus, AuditStatusEnum.REJECTED.getValue())
                    // 驳回理由给用户看：写 AI 的理由，作者才知道该改哪里
                    .set(StudioPost::getRejectReason, result.reason())
                    .set(StudioPost::getAuditAt, now);
        }
        // 灰色（REVIEW）与判定失败（FAILED）：status 保持待审，只把 AI 的建议写进 ai_reason

        int rows = baseMapper.update(null, wrapper);
        if (rows == 0) {
            // 更新不到说明管理员已在 AI 之前处置过，或这条已被判过——都不是错误
            log.debug("AI 结论未落库（已被人工处置或已判过）| postId={} | 结论={}",
                    postId, aiStatus.getDesc());
            return;
        }
        // 只记结论不记正文：正文可能含个人信息，不该进日志
        log.info("AI 审核完成 | postId={} | 结论={}", postId, aiStatus.getDesc());
    }

    /** 点赞帖子（幂等，只能赞、不能取消） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long likePost(long userId, long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        LambdaQueryWrapper<StudioPost> postWrapper = new LambdaQueryWrapper<>();
        postWrapper.eq(StudioPost::getId, postId)
                .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue());
        StudioPost post = this.getOne(postWrapper);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 查重：已赞过则原样返回当前计数——重复点赞不重复 +1（幂等）
        if (isLiked(postId, userId)) {
            return toLong(post.getLikeCount());
        }

        StudioPostLike like = new StudioPostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        try {
            ThrowUtils.throwIf(studioPostLikeMapper.insert(like) != 1,
                    ErrorCode.OPERATION_ERROR, "点赞失败");
        } catch (DuplicateKeyException e) {
            log.info("点赞撞唯一键（并发重复点赞），按幂等返回 | postId={} | userId={}", postId, userId);
            return toLong(post.getLikeCount());
        }

        // 自增用 setSql 而不是「读出来 +1 再写回」：后者在并发下互相覆盖，计数会偏小
        ThrowUtils.throwIf(!this.update(new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, postId)
                        .setSql("like_count = like_count + 1")),
                ErrorCode.OPERATION_ERROR, "点赞失败");

        // 事务提交后再读一次最新值：事务内读到的可能是旧快照
        return toLong(this.getById(postId).getLikeCount());
    }

    /** likeCount 为 null 时按 0 返回（历史数据可能没有该列的值） */
    private static long toLong(Integer count) {
        return count == null ? 0L : count.longValue();
    }

    /** 公开帖子列表（游客可访问） */
    @Override
    public Page<PostFrontVO> listApprovedByPage(long current, long pageSize, String keyword) {
        String trimmedKeyword = StrUtil.trimToNull(keyword);
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                .and(trimmedKeyword != null, w -> w
                        .like(StudioPost::getTitle, trimmedKeyword)
                        // 摘要不是全文：社区搜索定位的是「哪篇文章」，正文的深度检索交给 AI 助手
                        .or()
                        .like(StudioPost::getSummary, trimmedKeyword))
                .orderByDesc(StudioPost::getPinned)
                .orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);

        // 作者信息（姓名 + 头像）一次查完再内存填充——不要对每条帖子各查一次（那是 N+1）
        Map<Long, AuthorProfile> profiles = authorProfiles(entityPage.getRecords().stream()
                .map(StudioPost::getAuthorId).toList());
        Set<Long> likedPostIds = likedPostIds(entityPage.getRecords().stream()
                .map(StudioPost::getId).toList());

        Page<PostFrontVO> voPage = emptyVOPage(entityPage);
        List<PostFrontVO> records = new ArrayList<>(entityPage.getRecords().size());
        for (StudioPost post : entityPage.getRecords()) {
            PostFrontVO vo = new PostFrontVO();
            BeanUtils.copyProperties(post, vo);
            // 查不到档案时 name/avatar 都为 null：前端用姓名首字兜底，不影响列表渲染
            AuthorProfile author = profiles.get(post.getAuthorId());
            if (author != null) {
                vo.setAuthorName(author.name());
                vo.setAuthorAvatar(author.avatar());
            }
            vo.setLiked(likedPostIds.contains(post.getId()));
            records.add(vo);
        }
        voPage.setRecords(records);
        return voPage;
    }

    /** 帖子详情（游客可访问），并累加浏览量 */
    @Override
    public PostFrontDetailVO getApprovedDetail(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getId, id)
                .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue());
        StudioPost post = this.getOne(wrapper);
        // 待审 / 已驳回的帖子对公众等同于不存在——不暴露「这篇其实存在但没过审」
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在或尚未通过审核");

        this.update(new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, id)
                .setSql("view_count = view_count + 1"));

        PostFrontDetailVO detailVO = new PostFrontDetailVO();
        BeanUtils.copyProperties(post, detailVO);
        detailVO.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        // 只查一个作者也走批量方法：口径与列表页一致，避免两处各写一套取名逻辑
        AuthorProfile author = authorProfiles(List.of(post.getAuthorId())).get(post.getAuthorId());
        if (author != null) {
            detailVO.setAuthorName(author.name());
            detailVO.setAuthorAvatar(author.avatar());
        }
        // 游客也能看详情，因此当前用户可能为 null，isLiked 内部会直接返回 false 而不查库
        detailVO.setLiked(isLiked(id, currentUserIdOrNull()));
        return detailVO;
    }

    /** 我的发帖：含待审与已驳回，供作者跟进审核状态 */
    @Override
    public Page<PostVO> listMyByPage(long current, long pageSize) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();

        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        // 只按作者过滤、不带状态条件：三种状态作者都要能看到，否则驳回理由无处可查
        wrapper.eq(StudioPost::getAuthorId, userId)
                .orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /** 待审队列（先进先出） */
    @Override
    public Page<PostVO> listPendingByPage(long current, long pageSize) {
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /** 批量审核帖子（通过 / 驳回） */
    @Override
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

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(StudioPost::getId, request.getIds())
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .set(StudioPost::getStatus, approved
                        ? AuditStatusEnum.APPROVED.getValue()
                        : AuditStatusEnum.REJECTED.getValue())
                // 审核人写真人 ID：这是「人放的」与「机器放的」唯一的区分依据
                .set(StudioPost::getAuditBy, auditorId)
                .set(StudioPost::getAuditAt, LocalDateTime.now())
                .set(StudioPost::getUpdatedBy, auditorId)
                .set(StudioPost::getUpdatedAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPost::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }

        List<StudioPost> targets = this.list(new LambdaQueryWrapper<StudioPost>()
                .select(StudioPost::getId, StudioPost::getAuthorId, StudioPost::getTitle)
                .in(StudioPost::getId, request.getIds())
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue()));

        int rows = baseMapper.update(null, wrapper);

        for (StudioPost post : targets) {
            if (approved) {
                notificationService.notify(post.getAuthorId(), auditorId,
                        NotificationTypeEnum.POST_APPROVED.getValue(), "POST", post.getId(),
                        "你的帖子已通过审核",
                        "《" + post.getTitle() + "》已通过审核，现在所有访客可见");
            } else {
                notificationService.notify(post.getAuthorId(), auditorId,
                        NotificationTypeEnum.POST_REJECTED.getValue(), "POST", post.getId(),
                        "你的帖子被驳回",
                        "《" + post.getTitle() + "》未通过审核，理由：" + StrUtil.trim(request.getRejectReason()));
            }
        }

        if (rows > 0 && !targets.isEmpty()) {
            String detail = approved
                    ? "审核通过"
                    : "驳回理由：" + StrUtil.trim(request.getRejectReason());
            operationLogService.recordBatch(
                    approved ? OperationTypeEnum.POST_APPROVE : OperationTypeEnum.POST_REJECT,
                    targets.stream()
                            .map(post -> new OperationLogService.LogTarget(post.getId(), post.getTitle()))
                            .toList(),
                    detail);
        }
        return rows;
    }

    // 管理端：全量列表 / 删除 / 置顶 / 编辑

    /** 管理端帖子列表（全状态，支持状态 / 标题 / 作者筛选） */
    @Override
    public Page<PostVO> listForAdmin(PostAdminQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "查询参数不能为空");

        // 审核状态：精确匹配，取值合法性在这里校验（GET 参数不做注解校验，见 DTO 注释）
        Integer status = request.getStatus();
        if (status != null) {
            ThrowUtils.throwIf(AuditStatusEnum.of(status) == null, ErrorCode.PARAMS_ERROR,
                    "审核状态只能是 " + AuditStatusEnum.valuesText());
        }

        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(StudioPost::getStatus, status);
        }
        if (StrUtil.isNotBlank(request.getTitleKeyword())) {
            wrapper.like(StudioPost::getTitle, StrUtil.trim(request.getTitleKeyword()));
        }

        long current = request.getCurrent() == null ? 1L : request.getCurrent();
        long pageSize = request.getPageSize() == null ? DEFAULT_PAGE_SIZE : request.getPageSize();

        Collection<Long> authorIds = resolveAuthorIds(request.getAuthorKeyword());
        if (authorIds != null) {
            // 关键词一个用户都没命中：直接返回空页，不必再查帖子
            if (authorIds.isEmpty()) {
                return emptyVOPage(newPageWithDefaults(current, pageSize));
            }
            wrapper.in(StudioPost::getAuthorId, authorIds);
        }

        wrapper.orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /**
     * 把作者关键词解析成用户 ID 集合
     *
     * 账号与成员姓名都要匹配：门户展示用的是成员姓名，管理员照着屏幕上的名字搜必须能搜到。
     */
    private Collection<Long> resolveAuthorIds(String authorKeyword) {
        if (StrUtil.isBlank(authorKeyword)) {
            return null;
        }
        String keyword = StrUtil.trim(authorKeyword);
        Set<Long> ids = new HashSet<>();

        // 账号侧：昵称或账号名模糊匹配
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .and(nested -> nested
                        .like(SysUser::getUserName, keyword)
                        .or()
                        .like(SysUser::getUserAccount, keyword))
                .select(SysUser::getId));
        users.forEach(user -> ids.add(user.getId()));

        // 成员档案侧：姓名模糊匹配（在队与否都算——帖子不会因成员离队而消失）
        List<StudioMember> members = studioMemberMapper.selectList(new LambdaQueryWrapper<StudioMember>()
                .like(StudioMember::getName, keyword)
                .select(StudioMember::getId, StudioMember::getUserId));
        members.forEach(member -> {
            if (member.getUserId() != null) {
                ids.add(member.getUserId());
            }
        });
        return ids;
    }

    /** 删除帖子：同事务级联逻辑删除帖子 + 全部评论 + 全部点赞关系 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePost(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        ThrowUtils.throwIf(post.getStatus() == null
                        || post.getStatus() == AuditStatusEnum.PENDING.getValue(),
                ErrorCode.PARAMS_ERROR, "待审帖子不能删除，请使用驳回（作者需要收到理由）");

        long postId = post.getId();

        this.update(new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getUpdatedBy, operatorId));

        // 先取评论 ID：删完之后再查就查不到了，而这些 ID 正是关闭评论举报的依据
        List<Long> commentIds = studioPostCommentMapper.selectList(new LambdaQueryWrapper<StudioPostComment>()
                        .select(StudioPostComment::getId)
                        .eq(StudioPostComment::getPostId, postId))
                .stream()
                .map(StudioPostComment::getId)
                .toList();

        int commentRows = studioPostCommentMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId));

        studioPostLikeMapper.delete(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId));

        // 删帖子本体
        baseMapper.delete(new LambdaQueryWrapper<StudioPost>().eq(StudioPost::getId, postId));

        closePendingReports(ReportTargetTypeEnum.POST, List.of(postId), operatorId);
        closePendingReports(ReportTargetTypeEnum.COMMENT, commentIds, operatorId);

        // 删除是管理员对别人内容的操作，必须留痕（操作人已写入 updated_by，这里再记一条日志）
        log.info("管理员删除帖子 | postId={} | operatorId={} | 连带删除评论 {} 条", postId, operatorId, commentRows);
        // 日志与删除同事务：业务回滚则这条记录一起消失，不会留下「删了但没删」的假痕迹
        operationLogService.record(OperationTypeEnum.POST_DELETE, postId, post.getTitle(),
                "级联删除评论 " + commentRows + " 条，点赞关系同事务删除");
        // 返回内容行数（帖子 1 + 评论数）；点赞关系是物理删除，不计入
        return commentRows + 1;
    }

    /** 关闭一批目标上的待处理举报（内容已被删除） */
    private void closePendingReports(ReportTargetTypeEnum targetType, List<Long> targetIds, long operatorId) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        studioPostReportMapper.update(null, new LambdaUpdateWrapper<StudioPostReport>()
                .eq(StudioPostReport::getTargetType, targetType.getValue())
                .in(StudioPostReport::getTargetId, targetIds)
                .eq(StudioPostReport::getStatus, ReportStatusEnum.PENDING.getValue())
                .set(StudioPostReport::getStatus, ReportStatusEnum.RESOLVED.getValue())
                .set(StudioPostReport::getHandleBy, operatorId)
                .set(StudioPostReport::getHandleAt, now)
                .set(StudioPostReport::getHandleResult, "内容已被删除")
                .set(StudioPostReport::getUpdatedBy, operatorId)
                .set(StudioPostReport::getUpdatedAt, now));
    }

    /** 置顶 / 取消置顶 */
    @Override
    public void updatePinned(long id, int pinned) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        // 只接受 0 / 1：传 2 之类的值若直接入库，列表排序会得到无法解释的结果
        ThrowUtils.throwIf(pinned != 0 && pinned != 1, ErrorCode.PARAMS_ERROR,
                "置顶标记只能是 0（取消置顶）或 1（置顶）");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 只有「置顶」才要求已通过；取消置顶无条件放行，否则被编辑过的帖子会卡在置顶状态
        ThrowUtils.throwIf(pinned == 1 && (post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.APPROVED.getValue()),
                ErrorCode.PARAMS_ERROR, "只有已通过的帖子才能置顶（公开列表只展示已通过的内容）");

        // Wrapper 更新不触发审计填充器：置顶是对他人内容的处置，操作人必须显式写入
        ThrowUtils.throwIf(!this.update(new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, id)
                        .set(StudioPost::getPinned, pinned)
                        .set(StudioPost::getUpdatedBy, operatorId)
                        .set(StudioPost::getUpdatedAt, LocalDateTime.now())),
                ErrorCode.OPERATION_ERROR, pinned == 1 ? "置顶失败" : "取消置顶失败");

        log.info("帖子置顶状态已变更 | postId={} | pinned={} | operatorId={}", id, pinned, operatorId);
        // 置顶影响首页可见性，属于「对别人内容的处置」，必须能查到是谁调的
        operationLogService.record(OperationTypeEnum.POST_PIN, id, post.getTitle(),
                pinned == 1 ? "置顶" : "取消置顶");
    }

    /** 编辑帖子（仅管理员），非待审状态一律重置为待审重新送审 */
    @Override
    public void updatePost(PostUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "编辑内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();

        StudioPost post = this.getById(request.getId());
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 旧标题要在 applyPostEdit 之前取：编辑后正文与标题都会被覆盖，事后无从对比
        String oldTitle = post.getTitle();
        applyPostEdit(post, request, operatorId);
        log.info("管理员编辑帖子 | postId={} | operatorId={}", post.getId(), operatorId);

        // 代改会重置为待审，等于「原内容已被替换」——不留痕的话，审核结论对不上内容时无人可问
        String titleChange = Objects.equals(oldTitle, request.getTitle())
                ? "标题未变"
                : "标题「" + oldTitle + "」→「" + request.getTitle() + "」";
        operationLogService.record(OperationTypeEnum.POST_UPDATE, post.getId(), request.getTitle(),
                titleChange + "；保存后重置为待审重新送审");
    }

    /** 作者修改自己的帖子：待审 / 已驳回 / 已通过均可，保存后重新送审 */
    @Override
    public void updateMyPost(PostUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "修改内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        long userId = StpUtil.getLoginIdAsLong();

        StudioPost post = this.getById(request.getId());
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 只能改自己的：同码同提示，不暴露「这篇帖子其实存在但不是你的」
        ThrowUtils.throwIf(post.getAuthorId() == null || post.getAuthorId() != userId,
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 三种审核状态都允许作者改：已通过的改完会退回待审，审核通过前不再公开展示
        Integer status = post.getStatus();
        ThrowUtils.throwIf(status == null
                        || (status != AuditStatusEnum.PENDING.getValue()
                        && status != AuditStatusEnum.APPROVED.getValue()
                        && status != AuditStatusEnum.REJECTED.getValue()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        applyPostEdit(post, request, userId);
        log.info("作者修改帖子 | postId={} | authorId={} | 原状态={}", post.getId(), userId, status);
    }

    /** 改稿的公共实现（管理员代改与作者自助改共用） */
    private void applyPostEdit(StudioPost post, PostUpdateRequest request, long operatorId) {
        // 校验与 addPost 完全同源：正文长度 + XSS 兜底清洗 + 图片必须是站内路径
        String content = StrUtil.trim(request.getContent());
        ThrowUtils.throwIf(content.length() > CONTENT_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "正文过长，请控制在 " + CONTENT_MAX_LENGTH + " 字以内");
        content = MarkdownSanitizer.sanitize(content);
        assertImagesAreAllowed(content);

        // 摘要与封面的兜底规则同 addPost：不填也能有列表展示效果
        String summary = StrUtil.isBlank(request.getSummary())
                ? buildSummary(content)
                : StrUtil.trim(request.getSummary());
        String coverImage = StrUtil.isBlank(request.getCoverImage())
                ? extractFirstImage(content)
                : StrUtil.trim(request.getCoverImage());

        boolean backToPending = post.getStatus() == null
                || post.getStatus() != AuditStatusEnum.PENDING.getValue();

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPost::getId, post.getId())
                .set(StudioPost::getTitle, StrUtil.trim(request.getTitle()))
                .set(StudioPost::getSummary, summary)
                .set(StudioPost::getCoverImage, coverImage)
                .set(StudioPost::getContent, content)
                // 内容变了：审计列必须显式写（Wrapper 更新不传实体，填充器不会触发）
                .set(StudioPost::getUpdatedBy, operatorId)
                .set(StudioPost::getUpdatedAt, LocalDateTime.now())
                .set(StudioPost::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                .set(StudioPost::getAiReason, null)
                .set(StudioPost::getAiAt, null);
        if (backToPending) {
            wrapper.set(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                    .set(StudioPost::getAuditBy, null)
                    .set(StudioPost::getAuditAt, null)
                    .set(StudioPost::getRejectReason, null);
        }

        ThrowUtils.throwIf(!this.update(wrapper), ErrorCode.OPERATION_ERROR, "保存失败，数据库异常");

        long postId = post.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(postId, result));
    }

    /** 管理端详情：按 id 取单篇（含正文），不累加浏览量 */
    @Override
    public PostAdminDetailVO getPostForAdmin(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        PostAdminDetailVO vo = new PostAdminDetailVO();
        BeanUtils.copyProperties(post, vo);
        // 用管理端口径取名：老帖子的作者可能已离队，只查在队成员会让作者显示成空白
        vo.setAuthorName(adminAuthorNames(List.of(post.getAuthorId())).get(post.getAuthorId()));
        vo.setContent(post.getContent());
        return vo;
    }

    /** 作者读自己帖子的详情：仅本人；已通过的走这里不会累加浏览量，供改稿回填 */
    @Override
    public PostSelfDetailVO getMyPostDetail(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        long userId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 与 updateMyPost 同一条边界：非本人按「不存在」处理，不暴露存在性
        ThrowUtils.throwIf(post.getAuthorId() == null || post.getAuthorId() != userId,
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 三种审核状态都允许作者取回正文用于改稿
        ThrowUtils.throwIf(post.getStatus() == null
                        || (post.getStatus() != AuditStatusEnum.PENDING.getValue()
                        && post.getStatus() != AuditStatusEnum.APPROVED.getValue()
                        && post.getStatus() != AuditStatusEnum.REJECTED.getValue()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        PostSelfDetailVO vo = new PostSelfDetailVO();
        BeanUtils.copyProperties(post, vo);
        vo.setContent(post.getContent());
        return vo;
    }

    // 私有工具

    /** 校验正文里的图片：站内 /uploads/ 或已配置的 OSS/CDN 域名 */
    private void assertImagesAreAllowed(String content) {
        List<String> allowedHosts = ImageUrlWhitelist.parseHosts(postProperties.getAllowedImageHosts());
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(1);
            ThrowUtils.throwIf(!ImageUrlWhitelist.isAllowed(url, allowedHosts),
                    ErrorCode.PARAMS_ERROR,
                    "正文中的图片必须先用编辑器上传（" + ImageUrlWhitelist.INTERNAL_IMAGE_PREFIX
                            + "），或使用已配置的图片域名");
        }
    }

    /** 兜底摘要：剥掉常见 Markdown 标记后取前若干字 */
    private String buildSummary(String content) {
        String plain = content
                .replaceAll("!\\[[^\\]]*\\]\\([^)\\s]+\\)", "")   // 整张图片
                .replaceAll("\\[([^\\]]*)\\]\\([^)\\s]+\\)", "$1") // 链接保留文字
                .replaceAll("```.*?```", "")                     // 代码块
                .replaceAll("`([^`]*)`", "$1")                   // 行内代码保留内容
                .replaceAll("^#{1,6}\\s*", "")                   // 标题井号
                .replaceAll("[*#>\\-]", "")                      // 强调/引用/列表符号
                .replaceAll("\\s+", " ")
                .strip();
        return plain.length() <= SUMMARY_FALLBACK_LENGTH
                ? plain
                : plain.substring(0, SUMMARY_FALLBACK_LENGTH) + "…";
    }

    /** 取正文里第一张图片的地址作为封面 */
    private String extractFirstImage(String content) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 批量查作者信息（姓名 + 头像，一次查询，避免 N+1） */
    private Map<Long, AuthorProfile> authorProfiles(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getUserId, userIds)
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        List<StudioMember> members = studioMemberMapper.selectList(wrapper);

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

    /** 作者的展示信息（姓名 + 头像） */
    private record AuthorProfile(String name, String avatar) {
    }

    /** 当前登录用户 ID（未登录返回 null） */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        // 本项目登录统一传 Long 型用户 id，直接取
        return StpUtil.getLoginIdAsLong();
    }

    /** 批量查「当前用户赞了这批里的哪几篇」（一次查询，避免 N+1） */
    private Set<Long> likedPostIds(Collection<Long> postIds) {
        Long userId = currentUserIdOrNull();
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        List<StudioPostLike> likes = studioPostLikeMapper.selectList(new LambdaQueryWrapper<StudioPostLike>()
                .in(StudioPostLike::getPostId, postIds)
                .eq(StudioPostLike::getUserId, userId));
        return likes.stream().map(StudioPostLike::getPostId).collect(Collectors.toSet());
    }

    /** 当前用户是否已赞某篇（详情页与点赞查重共用） */
    private boolean isLiked(long postId, Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = studioPostLikeMapper.selectCount(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId)
                .eq(StudioPostLike::getUserId, userId));
        return count != null && count > 0;
    }

    /** 分页参数的兜底纠正：页码传错不必让整个查询失败，收敛到合理区间继续查 */
    private Page<StudioPost> newPageWithDefaults(long current, long pageSize) {
        long safeCurrent = current < 1 ? 1 : current;
        long safeSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        // 上限收敛：不设上限时一个 pageSize=100000 就能把整表读进内存
        safeSize = Math.min(safeSize, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    /** 构造一个保留 total/current/size 的空 VO 分页（前端分页组件依赖这三个值） */
    private <T> Page<T> emptyVOPage(Page<StudioPost> entityPage) {
        return new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
    }

    /** 实体页 → VO 页（批量填作者昵称，一次查询，不 N+1） */
    private Page<PostVO> toVOPage(Page<StudioPost> entityPage) {
        List<StudioPost> posts = entityPage.getRecords();
        Map<Long, String> names = adminAuthorNames(posts.stream().map(StudioPost::getAuthorId).toList());
        Page<PostVO> voPage = emptyVOPage(entityPage);
        voPage.setRecords(posts.stream()
                .map(post -> {
                    PostVO vo = new PostVO();
                    BeanUtils.copyProperties(post, vo);
                    vo.setAuthorName(names.get(post.getAuthorId()));
                    return vo;
                })
                .toList());
        return voPage;
    }

    /** 批量查作者展示名（管理端与作者本人视角共用） */
    private Map<Long, String> adminAuthorNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // 先按在队成员取名（与门户同一口径）
        Map<Long, String> names = new HashMap<>();
        authorProfiles(userIds).forEach((userId, profile) -> names.put(userId, profile.name()));

        // 成员档案里查不到的（已离队 / 未绑档案），用账号昵称兜底
        List<Long> missing = userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !names.containsKey(id))
                .distinct()
                .toList();
        if (missing.isEmpty()) {
            return names;
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, missing)
                .select(SysUser::getId, SysUser::getUserName, SysUser::getUserAccount));
        for (SysUser user : users) {
            String display = StrUtil.isNotBlank(user.getUserName()) ? user.getUserName() : user.getUserAccount();
            if (StrUtil.isNotBlank(display)) {
                names.put(user.getId(), display);
            }
        }
        return names;
    }
}

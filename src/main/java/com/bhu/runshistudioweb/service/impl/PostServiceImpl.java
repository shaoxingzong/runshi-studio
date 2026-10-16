package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.ContentAuditManager;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioPostCommentMapper;
import com.bhu.runshistudioweb.mapper.StudioPostLikeMapper;
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
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
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.vo.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.PostSelfDetailVO;
import com.bhu.runshistudioweb.model.vo.PostVO;
import com.bhu.runshistudioweb.model.vo.PostAdminDetailVO;
import com.bhu.runshistudioweb.service.PostService;
import com.bhu.runshistudioweb.service.StudioMemberService;
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
 * 帖子服务实现
 *
 * author: shaoshing
 *
 * <p><b>依赖都走构造器注入</b>（项目统一约定）：{@code final} 字段 + {@code @RequiredArgsConstructor}。
 *
 * <p><b>为什么注入 {@link StudioMemberMapper} 而不是只靠 {@link StudioMemberService}</b>：
 * 列表页要显示作者名，需要「按一批 user_id 反查成员姓名」这种<b>批量查询</b>能力，
 * 而 Service 对外只提供「按单个 userId 查在队成员」。批量查由 Mapper 直接做最直接，
 * 且能保证是一次查询而不是 N+1（见 {@link #authorNames}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<StudioPostMapper, StudioPost> implements PostService {

    /**
     * 正文长度上限
     *
     * <p>text 列能存得下更多，但正文会在「入库」和「返回详情」时被各传输一次，
     * 几十 MB 的输入足以拖垮两端。取一个对正常文章绰绰有余、又能挡住恶意输入的值。
     */
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

    /**
     * Markdown 图片语法：{@code ![任意描述](地址)}
     *
     * <p>只捕获地址部分。地址里不含空格与右括号——Markdown 的图片地址本来就不允许裸空格，
     * 所以这个简化正则够用，不必引入完整的 Markdown 解析器。
     */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    /** 站内图片路径前缀：只允许引用我们自己上传目录里的图片 */
    private static final String INTERNAL_IMAGE_PREFIX = "/uploads/";

    private final StudioMemberService studioMemberService;

    private final StudioMemberMapper studioMemberMapper;

    /**
     * 内容审核（AI 三分法）
     *
     * <p>只让它负责「判定」，落库由本类自己完成——依赖方向必须保持单向，
     * 反过来让 Manager 写库就会形成循环依赖（本项目已全量改为构造器注入，成环会启动即失败）。
     */
    private final ContentAuditManager contentAuditManager;

    /**
     * 点赞关系（studio_post_like）
     *
     * <p>注入 Mapper 而不是再包一层 Service：只需要「查重」与「按 post_id 批量查已赞集合」
     * 两种单表查询，与 {@link #studioMemberMapper} 的引入理由一致——
     * 批量能力走 Mapper 最直接，且能保证是一次查询而不是 N+1。
     */
    private final StudioPostLikeMapper studioPostLikeMapper;

    /**
     * 评论（studio_post_comment）
     *
     * <p>注入 Mapper 而非 {@code PostCommentService}：删帖子时要按 {@code post_id}
     * <b>批量</b>逻辑删除该帖的全部评论，这是单表批量能力，走 Mapper 最直接；
     * 且 Service 之间互相依赖容易成环（本项目已全量改为构造器注入，成环会启动即失败）。
     */
    private final StudioPostCommentMapper studioPostCommentMapper;

    /**
     * 用户（sys_user）
     *
     * <p>管理端列表要按作者关键词筛选、要展示作者昵称，都需要直接查账号表——
     * 这两种都是「按一批 id / 一个模糊词」的批量查询，与上述两个 Mapper 同一理由。
     */
    private final SysUserMapper sysUserMapper;

    @Override
    public long addPost(PostAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "发帖内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        // 与考勤同一套判定：认 studio_member 表，不认角色标签
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以发帖");

        String content = StrUtil.trim(request.getContent());
        ThrowUtils.throwIf(content.length() > CONTENT_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "正文过长，请控制在 " + CONTENT_MAX_LENGTH + " 字以内");

        // ① XSS 兜底：入库前白名单清洗（详见 MarkdownSanitizer）。
        //    放在最前面，让后续的图片校验、摘要/封面兜底、AI 送审看到的都是清洗后的内容
        content = MarkdownSanitizer.sanitize(content);

        // ② 图片必须是站内路径：外链图会成为追踪像素（泄露访客 IP），也可能将来裂图
        assertImagesAreInternal(content);

        StudioPost post = new StudioPost();
        post.setTitle(StrUtil.trim(request.getTitle()));
        post.setContent(content);
        // 先审后发：一律先落待审，仅作者在「我的发帖」里可见
        post.setStatus(AuditStatusEnum.PENDING.getValue());
        post.setAuthorId(userId);
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

        // 异步送 AI 初判：不阻塞提交，用户立刻拿到「已提交待审」的结果。
        // 闭包里只带 postId 与正文（都在请求线程里取好）——回调跑在虚拟线程上，
        // 那里拿不到 RequestContextHolder，不能再回头取登录态
        long newPostId = post.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(newPostId, result));
        return post.getId();
    }

    /**
     * 把 AI 的结论写回帖子（三分法处置）
     *
     * <p>更新条件有两个，缺一不可：
     * <ul>
     *     <li><b>未判</b>（{@link AiAuditStatusEnum#NOT_JUDGED}）：保证幂等，
     *     重复送审不会覆盖已有结论；</li>
     *     <li><b>待审</b>：管理员已经手动处置过的内容，后到的 AI 结论不能改写——
     *     <b>人的决定优先于机器</b>。</li>
     * </ul>
     *
     * <p><b>{@code audit_by} 一律留空</b>：AI 不是人，不该伪造一个管理员 ID。
     * 「这是机器处置的」这件事由 {@code ai_audit_status} 记录。
     *
     * @param postId 帖子 ID
     * @param result AI 结论（一定非空，见 {@code AuditResult} 的紧凑构造器）
     */
    private void applyAiVerdict(long postId, AuditResult result) {
        AiAuditStatusEnum aiStatus = result.status();
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPost::getId, postId)
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
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

    /**
     * 点赞帖子（幂等，<b>只能赞、不能取消</b>）
     *
     * <p><b>三步顺序不能乱</b>：先确认帖子可赞 → 再查重 → 最后才写。
     * 顺序反了会出现「给不存在的帖子点了赞」这种脏数据。
     *
     * <p><b>为什么用声明式事务</b>：这里两次写都是纯 DB 操作（insert 关系行 + update 计数），
     * 没有外部 HTTP 调用，与 {@code StudioProjectServiceImpl} 的「主表 + 关联表」同一类场景，
     * 用项目惯用的 {@code @Transactional(rollbackFor = Exception.class)} 即可。
     * （{@code AiChatServiceImpl} 要自建 {@code TransactionTemplate}，是因为它必须把
     * 几十秒的模型调用排除在事务外，这里没有这个问题。）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long likePost(long userId, long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        // ① 只允许赞「已通过」的帖子。待审 / 驳回对公众等同于不存在，
        //    因此与 getApprovedDetail 同一口径：同码（A0402）同提示，不暴露存在性
        LambdaQueryWrapper<StudioPost> postWrapper = new LambdaQueryWrapper<>();
        postWrapper.eq(StudioPost::getId, postId)
                .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue());
        StudioPost post = this.getOne(postWrapper);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // ② 查重：已赞过则原样返回当前计数——重复点赞不重复 +1（幂等）
        if (isLiked(postId, userId)) {
            return toLong(post.getLikeCount());
        }

        // ③ 未赞：插入关系行 + 计数原子自增。方法上的事务保证两次写要么都成功要么都回滚，
        //    不会出现「有关系行但计数没涨」的悬空状态
        StudioPostLike like = new StudioPostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        try {
            ThrowUtils.throwIf(studioPostLikeMapper.insert(like) != 1,
                    ErrorCode.OPERATION_ERROR, "点赞失败");
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过查重：后插入的撞唯一键 uk_post_user。
            // 语义上等同于「已经赞过」，按幂等返回，而不是抛个错让人反复点
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

    /** likeCount 为 null 时按 0 返回（老数据在加列之前可能没有值） */
    private static long toLong(Integer count) {
        return count == null ? 0L : count.longValue();
    }

    @Override
    public Page<PostFrontVO> listApprovedByPage(long current, long pageSize) {
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                .orderByDesc(StudioPost::getPinned)
                .orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);

        // 作者名一次查完再内存填充——不要对每条帖子各查一次作者（那是 N+1）
        Map<Long, String> names = authorNames(entityPage.getRecords().stream()
                .map(StudioPost::getAuthorId).toList());
        // 同理，「当前用户赞了这批里的哪几篇」也必须一次查完（likedPostIds），
        // 否则每篇一次查询就是标准的 N+1
        Set<Long> likedPostIds = likedPostIds(entityPage.getRecords().stream()
                .map(StudioPost::getId).toList());

        Page<PostFrontVO> voPage = emptyVOPage(entityPage);
        List<PostFrontVO> records = new ArrayList<>(entityPage.getRecords().size());
        for (StudioPost post : entityPage.getRecords()) {
            PostFrontVO vo = new PostFrontVO();
            BeanUtils.copyProperties(post, vo);
            vo.setAuthorName(names.get(post.getAuthorId()));
            vo.setLiked(likedPostIds.contains(post.getId()));
            records.add(vo);
        }
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public PostFrontDetailVO getApprovedDetail(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getId, id)
                .eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue());
        StudioPost post = this.getOne(wrapper);
        // 待审 / 已驳回的帖子对公众等同于不存在——不暴露「这篇其实存在但没过审」
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在或尚未通过审核");

        // 浏览量自增用 SQL 表达式而不是「读出来 +1 再写回」：
        // 后者在并发下会互相覆盖（两个请求同时读到 10，都写 11，实际只涨了 1）
        this.update(new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, id)
                .setSql("view_count = view_count + 1"));

        PostFrontDetailVO detailVO = new PostFrontDetailVO();
        BeanUtils.copyProperties(post, detailVO);
        detailVO.setAuthorName(authorNames(List.of(post.getAuthorId())).get(post.getAuthorId()));
        detailVO.setLiked(isLiked(id, currentUserIdOrNull()));
        return detailVO;
    }

    @Override
    public Page<PostVO> listMyByPage(long current, long pageSize) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();

        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getAuthorId, userId)
                .orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    @Override
    public Page<PostVO> listPendingByPage(long current, long pageSize) {
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
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
        // 驳回必须给理由：它是给作者看的，没有理由等于让作者自己猜哪里不合适
        ThrowUtils.throwIf(!approved && StrUtil.isBlank(request.getRejectReason()),
                ErrorCode.PARAMS_ERROR, "驳回时必须填写理由");

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(StudioPost::getId, request.getIds())
                // 只处理「待审」的：已通过/已驳回的不该被重复处理，
                // 否则会覆盖掉已有的审核结论（这也是一种幂等保护）
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .set(StudioPost::getStatus, approved
                        ? AuditStatusEnum.APPROVED.getValue()
                        : AuditStatusEnum.REJECTED.getValue())
                .set(StudioPost::getAuditBy, auditorId)
                .set(StudioPost::getAuditAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPost::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }
        // 一条 UPDATE 更新整批：批量审核的意义就在于此，不要退化成循环逐条 update。
        // 用 baseMapper.update 而不是 this.update——后者返回 boolean（是否成功），
        // 而这里要的是「实际影响的行数」，好让前端提示「已处理 N 条」
        return baseMapper.update(null, wrapper);
    }

    // ==================== 管理端：全量列表 / 删除 / 置顶 / 编辑 ====================

    @Override
    public Page<PostVO> listForAdmin(PostAdminQueryRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "查询参数不能为空");

        // ① 审核状态：精确匹配，取值合法性在这里校验（GET 参数不做注解校验，见 DTO 注释）
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

        // ② 作者关键词：先解析成一批 userId，再按 author_id IN 过滤。
        //    不写成 join：MP 的 LambdaQueryWrapper 表达不了 join，
        //    为一个模糊筛选去维护自定义 XML 不值得；sys_user 是几百条的量级，两次 IN 查询足够快
        // 分页参数先取好：GET 的 `?current=` 空串会绑成 null，不能直接拆箱
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

        // ③ 排序：最新提交在前。管理端翻找的是「最近发生了什么」，
        //    而待审队列专用的「先进先出」只在那一个接口用
        wrapper.orderByDesc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /**
     * 把作者关键词解析成用户 ID 集合
     *
     * <p><b>账号与成员姓名都要匹配</b>：门户展示用的是成员姓名，管理员照着屏幕上
     * 看到的名字去搜必须能搜到——否则「搜得到展示名却查不到帖子」会让人以为数据丢了。
     *
     * @param authorKeyword 作者关键词（可为空）
     * @return 用户 ID 集合；关键词为空时返回 {@code null}（表示「不按作者筛选」），
     *         与空集合（匹配不到任何人）是两种不同的语义
     */
    private Collection<Long> resolveAuthorIds(String authorKeyword) {
        if (StrUtil.isBlank(authorKeyword)) {
            return null;
        }
        String keyword = StrUtil.trim(authorKeyword);
        Set<Long> ids = new HashSet<>();

        // ① 账号侧：昵称或账号名模糊匹配
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .and(nested -> nested
                        .like(SysUser::getUserName, keyword)
                        .or()
                        .like(SysUser::getUserAccount, keyword))
                .select(SysUser::getId));
        users.forEach(user -> ids.add(user.getId()));

        // ② 成员档案侧：姓名模糊匹配（在队与否都算——帖子不会因成员离队而消失）
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

    /**
     * 删除帖子：同事务级联逻辑删除帖子 + 全部评论 + 全部点赞关系
     *
     * <p><b>为什么用声明式事务</b>：这里是多处纯 DB 写（审计痕迹、删评论、删点赞、删帖子），
     * 没有外部 HTTP 调用，用项目惯用的 {@code @Transactional(rollbackFor = Exception.class)} 即可。
     * 少了事务，「评论删了帖子没删掉」会留下父帖已不存在、评论还占着审核队列的中间态。
     *
     * <p><b>待审帖子不能删</b>（与 {@code PostCommentService#deleteComment} 同一条规则）：
     * 先审后发之下，作者只知道「我提交了」，直接删掉他什么都收不到；
     * 该走驳回——{@code rejectReason} 能把理由送到作者眼前。删帖只服务于
     * 「已通过的内容要下线」与「已驳回的内容要清理」两种场景。
     *
     * <p><b>审计说明</b>：{@code studio_post} 没有 {@code deleted_by} 列（评论表才有），
     * 因此删除前把 {@code updated_by} 显式写成操作人作为痕迹（元数据填充器只会填 0L），
     * 日志里再记一条；{@code deleted_at} 仍会记录删除时间。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePost(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // 待审 → 只能驳回：删了作者什么都收不到（他只知道「提交了」）。
        // 与 deleteComment「只删已通过」是同一条规则，提示语也指向同一个动作
        ThrowUtils.throwIf(post.getStatus() == null
                        || post.getStatus() == AuditStatusEnum.PENDING.getValue(),
                ErrorCode.PARAMS_ERROR, "待审帖子不能删除，请使用驳回（作者需要收到理由）");

        long postId = post.getId();

        // ① 审计痕迹：本表没有 deleted_by 列，删除前把 updated_by 显式写成操作人。
        //    元数据填充器只填 0L（拿不到登录态，见 MyMetaObjectHandler），审计必须自己写
        this.update(new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getUpdatedBy, operatorId));

        // ② 级联删评论（含楼中楼回复）：只删帖子会留下「父帖没了却还占审核队列」的孤儿
        //    this.remove 只返回 boolean，要实际条数故走 baseMapper.delete（MP 会改写成逻辑删除）
        int commentRows = studioPostCommentMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId));

        // ③ 级联删点赞关系：studio_post_like 是关系表、物理删除（见 DDL 注释），
        //    留着会变成挂在不存在帖子上的悬空行
        studioPostLikeMapper.delete(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId));

        // ④ 删帖子本体
        baseMapper.delete(new LambdaQueryWrapper<StudioPost>().eq(StudioPost::getId, postId));

        // 删除是管理员对**别人**内容的操作，必须留痕（操作人已写入 updated_by，这里再记一条日志）
        log.info("管理员删除帖子 | postId={} | operatorId={} | 连带删除评论 {} 条", postId, operatorId, commentRows);
        // 返回内容行数（帖子 1 + 评论数）；点赞关系是物理删除，不计入
        return commentRows + 1;
    }

    /**
     * 置顶 / 取消置顶
     *
     * <p>置顶只允许「已通过」的帖子（理由见 {@link PostService#updatePinned}）；
     * 取消置顶不设状态限制——恢复操作永远不该被拦住。
     */
    @Override
    public void updatePinned(long id, int pinned) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        ThrowUtils.throwIf(pinned != 0 && pinned != 1, ErrorCode.PARAMS_ERROR,
                "置顶标记只能是 0（取消置顶）或 1（置顶）");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        ThrowUtils.throwIf(pinned == 1 && (post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.APPROVED.getValue()),
                ErrorCode.PARAMS_ERROR, "只有已通过的帖子才能置顶（公开列表只展示已通过的内容）");

        ThrowUtils.throwIf(!this.update(new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, id)
                        .set(StudioPost::getPinned, pinned)),
                ErrorCode.OPERATION_ERROR, pinned == 1 ? "置顶失败" : "取消置顶失败");

        log.info("帖子置顶状态已变更 | postId={} | pinned={} | operatorId={}", id, pinned, operatorId);
    }

    /**
     * 编辑帖子（仅管理员），非待审状态一律重置为待审重新送审
     *
     * <p>重置时把上一轮内容的审核痕迹（AI 结论、审核人、审核时间、驳回理由）一并清掉：
     * 它们描述的是<b>旧版本</b>的结论，留着会让管理员误以为新内容已经判过。
     * 清空后由本方法重新送 AI 初判，走的是与发帖完全相同的链路。
     */
    @Override
    public void updatePost(PostUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "编辑内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();

        StudioPost post = this.getById(request.getId());
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        applyPostEdit(post, request);
        log.info("管理员编辑帖子 | postId={} | operatorId={}", post.getId(), operatorId);
    }

    /**
     * 作者修改自己被驳回的帖子（改完重新送审）
     *
     * <p><b>为什么作者只能改「已驳回」的</b>：待审帖正在队列里，让作者反复改会让
     * 审核对象不断变化（管理员可能正看着旧版本）；已通过的帖子更不允许改——
     * 那是公开内容，作者随手改等于给审核开后门（要改只能让管理员代改）。
     *
     * <p>改完与管理员代改走同一条链路：重置为待审、清掉上一轮审核痕迹、重新送 AI 初判。
     */
    @Override
    public void updateMyRejectedPost(PostUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "修改内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        long userId = StpUtil.getLoginIdAsLong();

        StudioPost post = this.getById(request.getId());
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        // ① 只能改自己的：同码同提示，不暴露「这篇帖子其实存在但不是你的」
        ThrowUtils.throwIf(post.getAuthorId() == null || post.getAuthorId() != userId,
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // ② 只能改已驳回的：其余状态一律按「不存在」处理（同口径，不暴露存在性）
        ThrowUtils.throwIf(post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.REJECTED.getValue(),
                ErrorCode.NOT_FOUND_ERROR, "只能修改已驳回的帖子");

        applyPostEdit(post, request);
        log.info("作者修改被驳回帖子 | postId={} | authorId={}", post.getId(), userId);
    }

    /**
     * 改稿的公共实现（管理员代改与作者自助改共用）
     *
     * <p><b>为什么抽出来</b>：两条入口的规则完全一致——校验正文、重置待审、
     * 清审核痕迹、重新送审。复制一份意味着将来改规则要改两处，
     * 而漏改任何一处都会造成「管理员改的会重新送审、作者改的不会」这类难查差异。
     *
     * <p>调用方负责自己的准入校验（管理员权限 / 作者本人 + 已驳回），本方法只管改稿本身。
     *
     * @param post    已取出的帖子实体（调用方保证非空且有权限）
     * @param request 改稿请求（标题、正文必填；摘要与封面选填）
     */
    private void applyPostEdit(StudioPost post, PostUpdateRequest request) {
        // 校验与 addPost 完全同源：正文长度 + XSS 兜底清洗 + 图片必须是站内路径
        String content = StrUtil.trim(request.getContent());
        ThrowUtils.throwIf(content.length() > CONTENT_MAX_LENGTH, ErrorCode.PARAMS_ERROR,
                "正文过长，请控制在 " + CONTENT_MAX_LENGTH + " 字以内");
        content = MarkdownSanitizer.sanitize(content);
        assertImagesAreInternal(content);

        // 摘要与封面的兜底规则同 addPost：不填也能有列表展示效果
        String summary = StrUtil.isBlank(request.getSummary())
                ? buildSummary(content)
                : StrUtil.trim(request.getSummary());
        String coverImage = StrUtil.isBlank(request.getCoverImage())
                ? extractFirstImage(content)
                : StrUtil.trim(request.getCoverImage());

        // 非待审（已通过 / 已驳回）→ 重置为待审：内容变了就必须重新过审，
        // 否则「先审后发」会被编辑功能绕开（改完的正文没人看过就对外）
        boolean backToPending = post.getStatus() == null
                || post.getStatus() != AuditStatusEnum.PENDING.getValue();

        LambdaUpdateWrapper<StudioPost> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(StudioPost::getId, post.getId())
                .set(StudioPost::getTitle, StrUtil.trim(request.getTitle()))
                .set(StudioPost::getSummary, summary)
                .set(StudioPost::getCoverImage, coverImage)
                .set(StudioPost::getContent, content);
        if (backToPending) {
            wrapper.set(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                    // 上一轮内容的审核痕迹全部作废（理由见方法注释）
                    .set(StudioPost::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                    .set(StudioPost::getAiReason, null)
                    .set(StudioPost::getAiAt, null)
                    .set(StudioPost::getAuditBy, null)
                    .set(StudioPost::getAuditAt, null)
                    .set(StudioPost::getRejectReason, null);
        }

        ThrowUtils.throwIf(!this.update(wrapper), ErrorCode.OPERATION_ERROR, "保存失败，数据库异常");

        // 重新送 AI 初判：与 addPost 同一条链路（异步、不阻塞保存）。
        // 编辑待审帖时同样要重送——它可能已经带了一轮针对旧正文的结论（灰色/判定失败），
        // 不重送则新正文永远不会被机器过一遍
        long postId = post.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(postId, result));
    }

    /**
     * 管理端详情：按 id 取单篇（含正文），<b>不累加浏览量</b>
     *
     * <p>理由见 {@link PostService#getPostForAdmin}：公开详情接口既不覆盖
     * 待审 / 已驳回的帖子，又会污染浏览量，管理动作必须绕开它。
     */
    @Override
    public PostAdminDetailVO getPostForAdmin(long id) {
        // 与其它管理端方法一致：Service 内也校验登录态，不把「调用方一定鉴权过」当作前提
        // （Controller 的 @SaCheckRole 是第一道，这里是第二道）
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");
        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        PostAdminDetailVO vo = new PostAdminDetailVO();
        BeanUtils.copyProperties(post, vo);
        vo.setAuthorName(adminAuthorNames(List.of(post.getAuthorId())).get(post.getAuthorId()));
        vo.setContent(post.getContent());
        return vo;
    }

    /**
     * 作者读自己未公开帖子的详情：仅本人 + 仅待审/已驳回，不累加浏览量
     *
     * <p>已通过的帖子走公开详情接口即可，这里刻意不覆盖——少一条能读到
     * 公开内容的私有路径，就少一处需要维护的边界。
     */
    @Override
    public PostSelfDetailVO getMyPostDetail(long id) {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        long userId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        StudioPost post = this.getById(id);
        ThrowUtils.throwIf(post == null, ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 与 updateMyRejectedPost 同一条边界：非本人按「不存在」处理，不暴露存在性
        ThrowUtils.throwIf(post.getAuthorId() == null || post.getAuthorId() != userId,
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        // 只覆盖待审与已驳回：已通过的走公开详情
        ThrowUtils.throwIf(post.getStatus() == null
                        || (post.getStatus() != AuditStatusEnum.PENDING.getValue()
                        && post.getStatus() != AuditStatusEnum.REJECTED.getValue()),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在");

        PostSelfDetailVO vo = new PostSelfDetailVO();
        BeanUtils.copyProperties(post, vo);
        vo.setContent(post.getContent());
        return vo;
    }

    // ==================== 私有工具 ====================

    /**
     * 校验正文里的图片全部是站内路径
     *
     * <p>为什么必须在服务端校验：编辑器只提供上传入口，但用户完全可以手改 Markdown
     * 塞一个 {@code ![](http://别的站点/xx.png)} 进来。前端拦不住，服务端这行能拦住，
     * 而且不需要维护任何域名白名单。
     */
    private void assertImagesAreInternal(String content) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(1);
            ThrowUtils.throwIf(!url.startsWith(INTERNAL_IMAGE_PREFIX),
                    ErrorCode.PARAMS_ERROR, "正文中的图片必须先用编辑器上传（" + INTERNAL_IMAGE_PREFIX + "），不支持外链图片");
        }
    }

    /**
     * 兜底摘要：剥掉常见 Markdown 标记后取前若干字
     *
     * <p>只处理最常见的几种标记（标题井号、图片、链接、代码块、强调符号），
     * 目的是「摘要里不要出现裸的 Markdown 语法」，不是完整解析 Markdown——
     * 那需要引入解析器，为一个兜底字段不值得。
     */
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

    /**
     * 取正文里第一张图片的地址作为封面
     *
     * @return 图片地址；正文无图时返回 null（列表页按"无封面"渲染）
     */
    private String extractFirstImage(String content) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 批量查作者名（一次查询，避免 N+1）
     *
     * @param userIds 作者 ID 集合
     * @return userId → 成员姓名
     */
    private Map<Long, String> authorNames(Collection<Long> userIds) {
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

    /**
     * 当前登录用户 ID（未登录返回 null）
     *
     * <p>游客的 {@code liked} 恒为 false，因此这里不查库。
     */
    private Long currentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        // 本项目登录统一传 Long 型用户 id，直接取
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 批量查「当前用户赞了这批里的哪几篇」（一次查询，避免 N+1）
     *
     * <p>照抄 {@link #authorNames} 的套路：一次查完，再在内存里用 Set 判断。
     * 游客不查库，直接返回空集合（{@code liked} 恒 false）。
     *
     * @param postIds 本页帖子 ID 集合
     * @return 已赞的帖子 ID 集合
     */
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

    /**
     * 当前用户是否已赞某篇（详情页与点赞查重共用）
     *
     * @param postId 帖子 ID
     * @param userId 用户 ID；为 null（游客）时直接返回 false，不查库
     */
    private boolean isLiked(long postId, Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = studioPostLikeMapper.selectCount(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId)
                .eq(StudioPostLike::getUserId, userId));
        return count != null && count > 0;
    }

    /**
     * 分页参数的兜底纠正：页码传错不必让整个查询失败，收敛到合理区间继续查
     */
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

    /**
     * 实体页 → VO 页（批量填作者昵称，一次查询，不 N+1）
     *
     * <p>「我的发帖」「待审队列」「管理端列表」三条路径共用——
     * {@link PostVO} 契约上 {@code authorName} 恒有值，而不是「有的接口填、有的不填」。
     */
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

    /**
     * 批量查作者展示名（管理端与作者本人视角共用）
     *
     * <p><b>为什么与 {@link #authorNames} 分开</b>：那个只认「在队成员」的姓名
     * （门户的对外口径）；而帖子不会因为成员毕业离队而消失，管理端要看到<b>所有</b>
     * 发过帖的账号——只查在队成员会让老帖子的作者变成空白。
     *
     * <p><b>口径与门户保持一致</b>：在队成员优先用成员姓名（否则同一篇帖子在门户显示
     * 「陈星野」、在后台显示账号名，管理员会以为数据出了问题）；查不到成员档案时
     * 兜底账号昵称，再兜底账号名——显示总比留空好。
     *
     * @param userIds 作者 ID 集合
     * @return userId → 展示名（可能没有值，调用方按 null 处理）
     */
    private Map<Long, String> adminAuthorNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // ① 先按在队成员取名（与门户同一口径）
        Map<Long, String> names = new HashMap<>(authorNames(userIds));

        // ② 成员档案里查不到的（已离队 / 未绑档案），用账号昵称兜底
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

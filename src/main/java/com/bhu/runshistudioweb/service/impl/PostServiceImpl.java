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
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.vo.post.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.post.PostSelfDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostVO;
import com.bhu.runshistudioweb.model.vo.post.PostAdminDetailVO;
import com.bhu.runshistudioweb.service.NotificationService;
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
 * 依赖都走构造器注入（项目统一约定）：final 字段 + @RequiredArgsConstructor。
 *
 * 为什么注入 StudioMemberMapper 而不是只靠 StudioMemberService：
 * 列表页要显示作者名，需要「按一批 user_id 反查成员姓名」这种批量查询能力，
 * 而 Service 对外只提供「按单个 userId 查在队成员」。批量查由 Mapper 直接做最直接，
 * 且能保证是一次查询而不是 N+1（见 authorNames）。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<StudioPostMapper, StudioPost> implements PostService {

    /**
     * 正文长度上限
     *
     * text 列能存得下更多，但正文会在「入库」和「返回详情」时被各传输一次，
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
     * Markdown 图片语法：![任意描述](地址)
     *
     * 只捕获地址部分。地址里不含空格与右括号——Markdown 的图片地址本来就不允许裸空格，
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
     * 只让它负责「判定」，落库由本类自己完成——依赖方向必须保持单向，
     * 反过来让 Manager 写库就会形成循环依赖（本项目全部使用构造器注入，成环会启动即失败）。
     */
    private final ContentAuditManager contentAuditManager;

    /**
     * 点赞关系（studio_post_like）
     *
     * 注入 Mapper 而不是再包一层 Service：只需要「查重」与「按 post_id 批量查已赞集合」
     * 两种单表查询，与 studioMemberMapper 的引入理由一致——
     * 批量能力走 Mapper 最直接，且能保证是一次查询而不是 N+1。
     */
    private final StudioPostLikeMapper studioPostLikeMapper;

    /**
     * 评论（studio_post_comment）
     *
     * 注入 Mapper 而非 PostCommentService：删帖子时要按 post_id
     * 批量逻辑删除该帖的全部评论，这是单表批量能力，走 Mapper 最直接；
     * 且 Service 之间互相依赖容易成环（本项目全部使用构造器注入，成环会启动即失败）。
     */
    private final StudioPostCommentMapper studioPostCommentMapper;

    /**
     * 站内通知（审核结论送达作者）
     *
     * 依赖方向单向：通知服务只做投递，不认识帖子/评论——
     * 「出什么事、标题正文怎么写」由本类（知道业务语义的一方）决定，
     * 投递策略（幂等 / 不给自己发 / 失败不拖垮）集中在 NotificationService。
     */
    private final NotificationService notificationService;

    /**
     * 用户（sys_user）
     *
     * 管理端列表要按作者关键词筛选、要展示作者昵称，都需要直接查账号表——
     * 这两种都是「按一批 id / 一个模糊词」的批量查询，与上述两个 Mapper 同一理由。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * 发布帖子（先审后发）
     *
     * <p>为什么这样设计：这是「先审后发」的入口，提交成功只代表「进了审核队列」，
     * 不代表「已公开」。落库时状态一律写「待审」，内容能否公开交给后面的
     * AI 初判与人工审核决定——若这里写「已通过」，整个审核环节就形同虚设。
     * 准入只校验登录态与 studio_member 表里的在队档案，不校验角色标签：
     * 成员身份是管理员维护的档案，角色只是权限标签，两者可能不同步
     * （有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
     * 摘要与封面为空时由服务端兜底生成，前端不必强制用户填。
     *
     * <p>执行步骤（与代码里的 ①② 标注一一对应，其余为不带编号的顺序校验）：
     * <ol>
     *     <li>校验登录态与在队成员身份——不知道「谁发的」，后面所有步骤都无从谈起；</li>
     *     <li>清洗正文（XSS 兜底）——必须放在最前面，让后续的图片校验、
     *     摘要与封面兜底、AI 送审看到的都是清洗后的内容；</li>
     *     <li>校验图片必须是站内路径——排在清洗之后，否则外链可能藏在被清掉的 HTML 里逃过检查；</li>
     *     <li>组装实体并落库为「待审」，同时兜底生成摘要与封面；</li>
     *     <li>异步送 AI 初判——不阻塞提交，用户提交完立刻拿到结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：把「发帖成功」理解成「内容已公开」。新帖在公开列表里查不到
     * 是设计如此，要确认状态得走「我的发帖」；另外回调里只能使用在请求线程里
     * 提前取好的变量，不能回头去取登录态（原因见方法内的行内注释）。
     *
     * @param request 发布请求（标题、正文必填；摘要与封面选填，为空时兜底生成），
     * 不能为 null；请求体里没有 status 字段，审核状态由服务端决定
     * @return 新帖子的 ID（雪花算法生成的 19 位长整型，不是数据库自增主键）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 非在队成员（A0301）、正文过长或含外链图片（A0401）、入库失败（B0001）时抛出
     */
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
     * <p>为什么这样设计：AI 的结论只对「还没人处置过」的内容有效。
     * 更新条件里带两个 eq，缺一不可——「未判」保证幂等（重复送审不会覆盖已有结论），
     * 「待审」保证管理员已经手动处置过的内容不会被后到的机器结论改写：
     * 人的决定优先于机器。audit_by 一律留空，因为 AI 不是人，
     * 不该伪造一个管理员 ID；「这是机器处置的」这件事由 ai_audit_status 记录。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先取出 AI 结论与当前时间，构造带两个 eq 条件的更新包装器；</li>
     *     <li>按三分法决定是否改 status：安全则通过、违规则驳回并写驳回理由，
     *     灰色与判定失败只记录 ai_reason、status 保持待审；</li>
     *     <li>执行更新；影响 0 行说明已被人工处置或已判过，这属于正常情况，
     *     记一条 debug 日志后直接返回，不能当成错误。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里抛异常。它跑在异步线程上，抛出去只会被
     * 线程池吞掉或记一条错误日志，内容照样停在待审——真正该做的是把
     * 「没写成」这件事如实记下来。另外日志里只记结论、不记正文，
     * 正文可能含个人信息，不该进日志。
     *
     * @param postId 帖子 ID，由调用方在提交时就固定好（回调里取不到登录态）
     * @param result AI 结论，一定非空——AuditResult 的紧凑构造器会把 null 兜成「转人工」
     */
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

    /**
     * 点赞帖子（幂等，只能赞、不能取消）
     *
     * <p>为什么这样设计：先查后写是刻意的顺序——必须先确认帖子可赞，
     * 再查重，最后才写；顺序反了会出现「给不存在的帖子点了赞」这种脏数据。
     * 本模块只能赞、不能取消，因此「重复点赞」必须幂等（幂等：重复调用与调用一次
     * 的结果一致），直接返回当前计数，不做第二次写。
     * 计数自增用 SQL 表达式而不是「读出来 +1 再写回」：后者在并发下
     * 两个请求会互相覆盖，计数偏小。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>只查「已通过」的帖子——待审 / 驳回对公众等同于不存在，
     *     与公开详情同一口径，同码同提示，不暴露存在性；</li>
     *     <li>查重：已赞过就原样返回当前计数，一次写都不做；</li>
     *     <li>插入关系行并原子自增计数，两步在同一事务里，
     *     不会出现「有关系行但计数没涨」的悬空状态。</li>
     * </ol>
     *
     * <p>容易踩的坑：并发下两个请求可能同时通过查重，后插入的会撞唯一键
     * uk_post_user。这不是错误，语义上等同于「已经赞过」，应当 catch 住
     * 按幂等返回，而不是抛个错让人反复点。
     *
     * @param userId 登录用户 ID（由 Controller 从登录态取，不接受请求体传入）
     * @param postId 被点赞的帖子 ID，必须大于 0，否则抛 A0401
     * @return 点赞后的最新点赞数（已赞过时原样返回，不重复 +1；计数列为空时按 0 返回）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核（A0402）、
     * 写入失败（B0002）时抛出
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

    /** likeCount 为 null 时按 0 返回（历史数据可能没有该列的值） */
    private static long toLong(Integer count) {
        return count == null ? 0L : count.longValue();
    }

    /**
     * 公开帖子列表（游客可访问）
     *
     * <p>为什么这样设计：只返回「已通过」，因为待审与已驳回的内容对公众
     * 等同于不存在。这个条件必须写进 SQL 的 WHERE，而不是查出来再在内存里过滤——
     * 后者会让分页总数把未过审的内容也算进去。排序是「置顶优先，再按创建时间倒序」：
     * 置顶是运营手段，必须压过时间。作者名与「我赞过没」都在这里批量补全
     * （各一次查询），绝不能对每条帖子各查一次——那是标准的 N+1
     * （查 1 次列表，再对每条记录各查 1 次）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按「状态 = 已通过」分页查询，置顶优先再按时间倒序；</li>
     *     <li>把这一页的作者 ID 一次性查成 userId → 姓名 的映射；</li>
     *     <li>同样一次性查出「当前用户赞了这一页里的哪几篇」；</li>
     *     <li>循环填充 VO，再塞回分页对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：BeanUtils.copyProperties 只拷「两边都存在」的属性，
     * 所以 PostFrontVO 天然拿不到正文与驳回理由；但 authorName 与 liked
     * 两个字段源实体里没有，必须手动补，漏掉任何一个前端都会显示空白。
     *
     * @param current 页码，小于 1 时按 1 处理（不报错，页码传错不该让查询失败）
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限（防一次拉全表）
     * @return 分页结果；没有已通过的帖子时返回空记录分页，不会返回 null
     */
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

        // 先建一个只保留 total/current/size 的空分页，再整体替换记录：
        // 直接 new Page 会丢掉 total，前端的分页组件就翻不了页
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

    /**
     * 帖子详情（游客可访问），并累加浏览量
     *
     * <p>为什么这样设计：浏览量用 SQL 表达式自增，而不是「读出来 +1 再写回」——
     * 后者在并发下会互相覆盖（两个请求同时读到 10，都写回 11，实际只涨了 1），
     * 而浏览量是内容热度的依据，被低估就很难再纠正。
     * 查询条件里同时带 id 与「状态 = 已通过」：待审 / 已驳回的帖子对公众
     * 等同于不存在，不能给出「存在但没过审」的额外信息。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验 id 合法性；</li>
     *     <li>按 id + 状态 = 已通过取单篇，取不到按「不存在」报错；</li>
     *     <li>原子自增浏览量（失败也不影响详情返回，所以不校验返回值）；</li>
     *     <li>填作者名与「当前用户是否赞过」，组装详情 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：自增语句不能加状态条件之外的东西，也不要把它改成
     * 先查实体、改字段、再 update——那样又回到了读改写竞态。
     * 另外游客也要能看详情，所以「是否赞过」必须允许当前用户为空。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含 Markdown 正文）；作者名在成员档案缺失时为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
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
        // 只查一个作者也走批量方法：口径与列表页一致，避免两处各写一套取名逻辑
        detailVO.setAuthorName(authorNames(List.of(post.getAuthorId())).get(post.getAuthorId()));
        // 游客也能看详情，因此当前用户可能为 null，isLiked 内部会直接返回 false 而不查库
        detailVO.setLiked(isLiked(id, currentUserIdOrNull()));
        return detailVO;
    }

    /**
     * 我的发帖：含待审与已驳回，供作者跟进审核状态
     *
     * <p>为什么这样设计：这是「先审后发」必须配套的出口。作者提交后看不到
     * 公开列表里的自己，必须有一个地方能看到状态与驳回理由，
     * 否则「发出去了但没显示」会被理解成系统故障。因此本方法刻意不加状态过滤，
     * 只按 author_id 限定「看谁的」——这是它与公开列表最关键的区别。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态并取当前用户 ID——不登录就没有「我的」这个主体；</li>
     *     <li>按 author_id 分页查询，创建时间倒序（作者关心的是最近发的）；</li>
     *     <li>转成 VO 分页并批量填作者名，与其它列表接口保持同一契约。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「复用代码」直接套用公开列表的查询条件。
     * 那里带 status = 已通过，作者自己的待审稿会被过滤掉，
     * 于是「我的发帖」永远是空的——而作者恰恰最需要看到这些。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果（含状态、驳回理由、AI 初判结论）；该作者没有帖子时返回空记录分页
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
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

    /**
     * 待审队列（先进先出）
     *
     * <p>为什么这样设计：按创建时间升序而不是倒序——审核是排队作业，
     * 先提交的应当先被处理，否则老内容会被不断涌入的新内容压到队尾。
     * 在「先审后发」下这一点尤其重要：作者提交后看不到自己的内容，
     * 若队列顺序让某条永远沉底，它等于被静默丢弃了。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>按「状态 = 待审」分页查询，创建时间升序；</li>
     *     <li>转成 VO 分页并批量填作者名，管理员要能看出「这是谁提交的」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验登录态与管理员角色——这两道在 Controller 上
     * 由 @SaCheckRole 拦下。队列里是全站所有用户的待审内容，
     * 换给普通用户调用就是一次内容泄露。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    @Override
    public Page<PostVO> listPendingByPage(long current, long pageSize) {
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        return toVOPage(entityPage);
    }

    /**
     * 批量审核帖子（通过 / 驳回）
     *
     * <p>为什么这样设计：一条 UPDATE 处理整批，而不是循环逐条更新——
     * 批量审核的意义就在于此，逐条更新会把「勾选 20 条一次通过」变成 20 次往返。
     * 更新条件里必须带「状态 = 待审」：已通过 / 已驳回的记录不该被重复处理，
     * 否则后一次审核会覆盖掉前一次的结论，这也是幂等保护。
     * 驳回必须填理由——理由要给作者看，没有理由等于让作者自己猜哪里不合适。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态、动作取值与驳回理由；</li>
     *     <li>按「id 在批次内 + 状态 = 待审」查出真正会被处理的帖子，
     *     这一步必须排在更新之前——一条 UPDATE 拿不到作者，通知只能靠这份名单；</li>
     *     <li>一条 UPDATE 更新整批并拿到受影响行数；</li>
     *     <li>逐个给作者发通知，放在更新之后：审核失败会抛异常，自然走不到通知。</li>
     * </ol>
     *
     * <p>容易踩的坑：不能把 this.update 的布尔返回值当成处理条数，
     * 它只表示成功与否；要「已处理 N 条」这个数字，必须用 baseMapper.update
     * 的返回值（受影响行数）。另外查名单与执行更新必须用完全相同的条件，
     * 否则会出现「通知发了、实际没改」的错位。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、
     * 驳回时 rejectReason 必填），不能为 null
     * @return 实际处理的条数（已在审核前被处理过的不计入，因此可能小于传入的 id 个数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 动作非法或驳回缺理由（A0401）时抛出
     */
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
                // 审核人写真人 ID：这是「人放的」与「机器放的」唯一的区分依据
                .set(StudioPost::getAuditBy, auditorId)
                .set(StudioPost::getAuditAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPost::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }

        // 通知要发给每个被处理帖子的作者，而一条 UPDATE 拿不到作者——
        // 先按同一条件查出实际会被处理的帖子（只要 id / authorId / title 三列）。
        // 这与更新条件完全一致，查不到的（已处理/不存在）本来也不该收到通知
        List<StudioPost> targets = this.list(new LambdaQueryWrapper<StudioPost>()
                .select(StudioPost::getId, StudioPost::getAuthorId, StudioPost::getTitle)
                .in(StudioPost::getId, request.getIds())
                .eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue()));

        // 一条 UPDATE 更新整批：批量审核的意义就在于此，不要退化成循环逐条 update。
        // 用 baseMapper.update 而不是 this.update——后者返回 boolean（是否成功），
        // 而这里要的是「实际影响的行数」，好让前端提示「已处理 N 条」
        int rows = baseMapper.update(null, wrapper);

        // 审核结论逐个送达：批量审核省的是管理员的时间，不该连作者的知情权一起省。
        // 放在更新成功之后——审核失败会抛异常，自然走不到这里（不成功不通知）。
        // notify 内部幂等 + 不给自己发 + 失败只记日志，调用方不需要额外防护
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
        return rows;
    }

    // ==================== 管理端：全量列表 / 删除 / 置顶 / 编辑 ====================

    /**
     * 管理端帖子列表（全状态，支持状态 / 标题 / 作者筛选）
     *
     * <p>为什么这样设计：作者关键词先解析成一批 userId 再按 author_id IN 过滤，
     * 而不是写 JOIN——为一个模糊筛选维护手写 SQL 不值得，
     * 且 sys_user 只有几百条，两次 IN 查询足够快。
     * 三个筛选条件彼此独立，任一为空就表示「不按它筛」，而不是「查该字段为空的行」。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>校验审核状态取值——GET 参数不做注解校验，非法值必须在这里挡住，
     *     否则会把一个空结果当成「没有数据」；</li>
     *     <li>解析作者关键词：命中不到任何人时直接返回空页，不必再查帖子；</li>
     *     <li>按创建时间倒序分页查询并转成 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：分页字段是包装类型 Long，GET 的 `?current=` 传空串会绑成 null，
     * 直接拆箱会 NPE，所以必须先判空再取默认值。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页），不能为 null
     * @return 分页结果（含作者展示名）；筛选不到任何内容时返回空记录分页
     * @throws com.bhu.runshistudioweb.exception.BusinessException 查询参数为 null、
     * 审核状态取值非法时抛出（A0401）
     */
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
     * <p>为什么这样设计：账号与成员姓名都要匹配。门户展示用的是成员姓名，
     * 管理员照着屏幕上看到的名字去搜必须能搜到——否则「搜得到展示名却查不到帖子」
     * 会让人以为数据丢了。两次查询各自只取需要的列，避免把整行数据读回来。
     *
     * <p>执行步骤（与代码里的 ①② 标注一一对应）：
     * <ol>
     *     <li>账号侧：昵称或账号名模糊匹配，命中即加入集合；</li>
     *     <li>成员档案侧：姓名模糊匹配，用档案上的 user_id 加入集合
     *     （在队与否都算——帖子不会因成员离队而消失）。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值有三种语义，调用方必须分清——null 表示「不按作者筛选」，
     * 空集合表示「匹配不到任何人」，非空集合才是真正的筛选条件。
     * 把 null 和空集合混为一谈，会让「不筛选」变成「查不到任何东西」。
     * 另外成员档案的 user_id 允许为空（未绑定账号的档案），必须跳过，
     * 否则会把 null 拼进 IN 条件。
     *
     * @param authorKeyword 作者关键词，可为空
     * @return 用户 ID 集合；关键词为空时返回 null（表示不按作者筛选）
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
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里，
     * 查询时由框架自动追加 deleted_at = 0 过滤）能保住审计线索，所以帖子本身
     * 不物理删除；但只删帖子会留下两类孤儿——评论还占着审核队列、
     * 点赞关系还挂在已不存在的帖子上，因此三处写必须在同一事务里完成。
     * 少了事务，「评论删了帖子没删掉」会留下父帖已不存在、
     * 评论还占着审核队列的中间态。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>审计痕迹：本表没有 deleted_by 列，删除前把 updated_by 显式写成操作人；</li>
     *     <li>级联删评论（含楼中楼回复）——只删帖子会留下「父帖没了却还占审核队列」的孤儿；</li>
     *     <li>级联删点赞关系——关系表是物理删除，留着就是悬空行；</li>
     *     <li>最后删帖子本体：顺序不能提前，否则中途失败会留下「父帖已删、子行还在」。</li>
     * </ol>
     *
     * <p>容易踩的坑：待审帖子不能删（与删除评论同一条规则）。先审后发之下
     * 作者只知道「我提交了」，直接删掉他什么都收不到；该走驳回，
     * 让 rejectReason 把理由送到作者眼前。删帖只服务于「已通过的内容要下线」
     * 与「已驳回的内容要清理」两种场景。另外元数据填充器只会把 updated_by
     * 填成 0L（它拿不到登录态），所以审计痕迹必须自己写。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、帖子尚在待审（A0401）时抛出
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

        // 删除是管理员对别人内容的操作，必须留痕（操作人已写入 updated_by，这里再记一条日志）
        log.info("管理员删除帖子 | postId={} | operatorId={} | 连带删除评论 {} 条", postId, operatorId, commentRows);
        // 返回内容行数（帖子 1 + 评论数）；点赞关系是物理删除，不计入
        return commentRows + 1;
    }

    /**
     * 置顶 / 取消置顶
     *
     * <p>为什么这样设计：置顶只允许「已通过」的帖子——公开列表只返回已通过的内容，
     * 置顶一篇待审帖在门户上没有任何效果，属于典型误操作，因此在服务端挡掉
     * 并给出可读提示，而不是让用户点了没反应。取消置顶则刻意不设状态限制：
     * 帖子被编辑后会重置为待审，但 pinned 仍为 1，若此时不许取消置顶，
     * 管理员就卡在一个「改不回来」的状态里。恢复操作永远不该被拦住。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态、id 与 pinned 取值（只接受 0 与 1）；</li>
     *     <li>取出帖子，不存在报 A0402；</li>
     *     <li>置顶时再校验状态必须是已通过（取消置顶跳过这一步）；</li>
     *     <li>按 id 更新 pinned，更新失败抛 B0002。</li>
     * </ol>
     *
     * <p>容易踩的坑：把状态校验写成「无条件要求已通过」，会让被编辑过的帖子
     * 既不能置顶也不能取消置顶，等于把数据锁死。校验条件里要先判 null 再比较，
     * 因为 status 列理论上可能为空（脏数据），直接拆箱会 NPE。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、取值非法或对非已通过帖子置顶（A0401）、更新失败（B0002）时抛出
     */
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

        ThrowUtils.throwIf(!this.update(new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, id)
                        .set(StudioPost::getPinned, pinned)),
                ErrorCode.OPERATION_ERROR, pinned == 1 ? "置顶失败" : "取消置顶失败");

        log.info("帖子置顶状态已变更 | postId={} | pinned={} | operatorId={}", id, pinned, operatorId);
    }

    /**
     * 编辑帖子（仅管理员），非待审状态一律重置为待审重新送审
     *
     * <p>为什么这样设计：编辑权只给管理员。「先审后发」意味着内容公开前已过审，
     * 若作者能自由修改已通过的正文，等于给审核开后门——改完的内容没人再看。
     * 编辑后必须重新送审，否则「先审后发」会被编辑功能绕开。
     * 重置时把上一轮内容的审核痕迹（AI 结论、审核人、审核时间、驳回理由）一并清掉：
     * 它们描述的是修改前那份内容的结论，留着会让管理员误以为新内容已经判过。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验请求体与登录态；</li>
     *     <li>取出帖子，不存在报 A0402——不判状态，管理员三类状态都要能改；</li>
     *     <li>走公共改稿逻辑：校验正文、重置待审、清审核痕迹、重新送 AI 初判。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验管理员角色，这道在 Controller 的
     * @SaCheckRole(ADMIN) 上。若把本方法暴露给普通用户，
     * 就等于把「绕过审核改内容」做成一个接口。
     *
     * @param request 编辑请求（id、标题、正文必填；摘要与封面选填，为空时兜底生成），
     * 不能为 null；请求体里没有 status 字段
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、正文过长或含外链图片（A0401）、保存失败（B0002）时抛出
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
     * <p>为什么这样设计：与管理员代改共用同一套改稿实现，差别只在准入——
     * 本方法额外要求「是作者本人」且「状态为已驳回」。待审帖不许作者改：
     * 它正在队列里，让作者反复改会让审核对象不断变化（管理员可能正看着修改前的内容）；
     * 已通过的帖子更不允许——那是公开内容，作者随手改等于给审核开后门。
     * 这两种情况都按「帖子不存在」返回，不暴露存在性。
     *
     * <p>执行步骤（与代码里的 ①② 标注一一对应）：
     * <ol>
     *     <li>校验作者本人——同码同提示，不让外人探知「这篇存在但不是你的」；</li>
     *     <li>校验状态为已驳回——其余状态一律按不存在处理；</li>
     *     <li>走公共改稿逻辑：重置待审、清审核痕迹、重新送 AI 初判。</li>
     * </ol>
     *
     * <p>容易踩的坑：两个校验的先后不能换。先判状态再判本人，
     * 会让外人通过「提示语不同」反推出这篇帖子当前处于什么状态。
     *
     * @param request 改稿请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是已驳回（均 A0402）、正文过长或含外链图片（A0401）、
     * 保存失败（B0002）时抛出
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
     * <p>为什么这样设计：两条入口的规则完全一致——校验正文、重置待审、
     * 清审核痕迹、重新送审。复制一份意味着将来改规则要改两处，
     * 而漏改任何一处都会造成「管理员改的会重新送审、作者改的不会」这类难查差异。
     * 调用方负责自己的准入校验（管理员权限 / 作者本人 + 已驳回），本方法只管改稿本身。
     * 清空上一轮审核痕迹是必须的：ai_status、audit_by、audit_at、reject_reason
     * 描述的都是修改前那份内容的结论，留着会让管理员误以为新内容已经判过。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验正文长度、清洗正文、校验图片是站内路径——与发帖完全同源；</li>
     *     <li>兜底生成摘要与封面；</li>
     *     <li>判断是否需要重置为待审（非待审的才需要）；</li>
     *     <li>更新正文相关字段，需要重置时一并清空审核痕迹；</li>
     *     <li>重新送 AI 初判——与发帖同一条链路。</li>
     * </ol>
     *
     * <p>容易踩的坑：编辑「待审」帖时同样要重新送 AI 判。它可能已经带了一轮
     * 针对旧正文的结论（灰色 / 判定失败），不重送则新正文永远不会被机器过一遍。
     * 另外清空痕迹必须用 set(..., null) 显式写入，不能指望「不传就不改」——
     * 那样旧结论会原样留着。
     *
     * @param post 已取出的帖子实体，调用方保证非空且有权限
     * @param request 改稿请求（标题、正文必填；摘要与封面选填）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 正文过长或含外链图片（A0401）、
     * 保存失败（B0002）时抛出
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
     * 管理端详情：按 id 取单篇（含正文），不累加浏览量
     *
     * <p>为什么这样设计：公开详情接口既不覆盖待审 / 已驳回的帖子，
     * 又会原子自增浏览量——管理员点开编辑一下计数就 +1，
     * 而浏览量是内容热度的依据，不能被管理动作污染。因此管理端必须有自己的
     * 取详情路径，且这里一个计数都不碰。Service 内也校验登录态，
     * 不把「调用方一定鉴权过」当作前提（Controller 的 @SaCheckRole 是第一道，这里是第二道）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态与 id 合法性；</li>
     *     <li>按 id 取单篇，不看审核状态；</li>
     *     <li>转成管理端 VO，填作者展示名并带上正文。</li>
     * </ol>
     *
     * <p>容易踩的坑：顺手在这里加一句浏览量自增，会让管理员每打开一次编辑弹窗
     * 就给内容热度灌一次水，而且这种污染很难从数据上看出来。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文），三种审核状态都会返回；作者展示名查不到时为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在时抛出（A0402）
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
        // 用管理端口径取名：老帖子的作者可能已离队，只查在队成员会让作者显示成空白
        vo.setAuthorName(adminAuthorNames(List.of(post.getAuthorId())).get(post.getAuthorId()));
        vo.setContent(post.getContent());
        return vo;
    }

    /**
     * 作者读自己未公开帖子的详情：仅本人 + 仅待审/已驳回，不累加浏览量
     *
     * <p>为什么这样设计：改稿的前置步骤。驳回稿的正文作者本来读不到
     * （公开详情只返回已通过），要改就得先看到自己写了什么。
     * 已通过的帖子走公开详情接口即可，这里刻意不覆盖——少一条能读到
     * 公开内容的私有路径，就少一处需要维护的边界。
     * 非本人一律按「不存在」返回，不暴露存在性。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>校验登录态并取当前用户 ID；</li>
     *     <li>按 id 取帖子，不存在报 A0402；</li>
     *     <li>校验作者是本人——与改稿同一条边界；</li>
     *     <li>校验状态是待审或已驳回，其余按不存在处理；</li>
     *     <li>组装 VO 并带上正文。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不能顺手加浏览量自增——作者反复打开自己的草稿
     * 不该被算成「有人看过」，而且待审稿本来就没有对外曝光。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是待审或已驳回（均 A0402）时抛出
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
     * <p>为什么这样设计：必须在服务端校验。编辑器只提供上传入口，
     * 但用户完全可以手改 Markdown 塞一个外链图片进来，前端拦不住。
     * 外链图有两个实际风险：它会成为追踪像素（访客打开页面就泄露 IP），
     * 以及将来对方删图后页面出现裂图。服务端这一行能拦住，且不需要维护域名白名单。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用正则逐个取出正文里的图片地址；</li>
     *     <li>逐个判断是否以站内前缀开头，不是就抛错并提示上传方式。</li>
     * </ol>
     *
     * <p>容易踩的坑：正则只认标准 Markdown 图片语法，且地址里不含空格——
     * 这是刻意接受的简化：地址里带裸空格本来就不是合法 Markdown。
     * 因此不必为了「覆盖所有写法」引入完整的 Markdown 解析器。
     *
     * @param content 已清洗过的正文
     * @throws com.bhu.runshistudioweb.exception.BusinessException 存在非站内图片地址时抛出（A0401）
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
     * <p>为什么这样设计：只处理最常见的几种标记（标题井号、图片、链接、代码块、
     * 强调符号），目的是「摘要里不要出现裸的 Markdown 语法」，而不是完整解析 Markdown——
     * 那需要引入解析器，为一个兜底字段不值得。摘要在入库时算好并落库，
     * 读取时不再现算：否则列表页一页 20 条就是 20 次字符串处理，结果还不稳定。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>整张图片直接删掉（摘要里留一串路径没有意义）；</li>
     *     <li>链接只保留文字；代码块删掉、行内代码保留内容；</li>
     *     <li>去掉标题井号与强调符号，把连续空白压成一个空格；</li>
     *     <li>超过上限就截断并补省略号。</li>
     * </ol>
     *
     * <p>容易踩的坑：替换顺序不能随意调换。若先删强调符号再处理链接，
     * 链接语法里的方括号与括号会先被破坏，后面就匹配不上了。
     *
     * @param content 已清洗过的正文，不应为 null（调用方已做过 trim）
     * @return 纯文本摘要，最长 SUMMARY_FALLBACK_LENGTH 个字；正文全是标记时可能返回空串
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
     * <p>为什么这样设计：封面为空时用正文首图兜底，用户不填也能有列表缩略图。
     * 只取第一张是因为列表页只需要一张——多取没有任何用处，反而要多解析一遍全文。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>用与图片校验相同的正则匹配；</li>
     *     <li>命中就返回第一段的地址，否则返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 null 是正常结果（正文无图），调用方必须允许
     * coverImage 为空；不要在这里返回空串，前端对 null 与空串的处理往往不同。
     *
     * @param content 已清洗过的正文
     * @return 图片地址；正文无图时返回 null（列表页按「无封面」渲染）
     */
    private String extractFirstImage(String content) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 批量查作者名（一次查询，避免 N+1）
     *
     * <p>为什么这样设计：列表页要为每条记录填作者名，若逐条查询就是标准的 N+1
     * （查 1 次列表，再对每条记录各查 1 次）——一页 10 条就是 10 次额外查询。
     * 改成一次 IN 查询后，查询次数与页大小无关。
     * 只认「在队成员」的姓名：这是门户的对外口径，离队成员的帖子
     * 不该在门户上继续署名（管理端另有 adminAuthorNames 兜底）。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>空集合直接返回空映射，省掉一次无意义的 SQL；</li>
     *     <li>按 user_id IN 且 member_status = 在队，一次查出成员档案；</li>
     *     <li>在内存里拼成 userId → 姓名 的映射，跳过姓名为空与未绑定账号的档案。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的映射可能缺少某些 userId（作者已离队或没绑档案），
     * 调用方取值后必须允许为 null，不能假定「每个作者都查得到名字」。
     *
     * @param userIds 作者 ID 集合，可为 null 或空
     * @return userId → 成员姓名；入参为空时返回空映射（不是 null）
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
            // 未绑定账号的档案 user_id 为空，姓名也可能没填——两者都要跳过，否则映射里会出现 null 键
            if (member.getUserId() != null && StrUtil.isNotBlank(member.getName())) {
                names.put(member.getUserId(), member.getName());
            }
        }
        return names;
    }

    /**
     * 当前登录用户 ID（未登录返回 null）
     *
     * <p>为什么这样设计：游客的 liked 恒为 false，因此未登录时不查库、直接返回 null。
     * 用一个「可能为 null」的返回值而不是抛异常：公开详情本来就要允许游客访问，
     * 未登录不是错误。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>先判是否登录；</li>
     *     <li>已登录则取 Long 型用户 id（本项目登录统一存 Long，不需要再转换）。</li>
     * </ol>
     *
     * <p>容易踩的坑：调用方拿到 null 必须当成「游客」处理，
     * 不能直接拆箱成 long——那会 NPE，而且游客访问详情是最常见的场景。
     *
     * @return 当前登录用户 ID；未登录返回 null
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
     * <p>为什么这样设计：列表页每条记录都要标出「我赞过没」。若逐条查询，
     * 一页 10 条就是 10 次额外查询（标准 N+1）；改成一次 IN 查询后，
     * 查询次数与页大小无关。游客不查库，直接返回空集合——
     * 未登录用户的 liked 恒为 false，没有查的必要。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>游客或空集合直接返回空集合，省掉 SQL；</li>
     *     <li>按「post_id IN 本页 + user_id = 当前用户」一次查出点赞关系；</li>
     *     <li>把结果映射成帖子 ID 集合，供调用方用 contains 判断。</li>
     * </ol>
     *
     * <p>容易踩的坑：条件里必须同时带 user_id，否则会把「所有人的点赞」
     * 都算成当前用户赞过——这既是性能问题，也是隐私问题。
     *
     * @param postIds 本页帖子 ID 集合，可为 null 或空
     * @return 已赞的帖子 ID 集合；游客或入参为空时返回空集合（不是 null）
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
     * <p>为什么这样设计：详情页要标「我赞过没」，点赞接口要查重，
     * 两处语义完全相同，因此共用同一个私有方法——复制一份意味着将来
     * 改判定口径要改两处，漏改就会出现「详情显示已赞、点赞却还能再赞一次」。
     * 游客直接返回 false 而不查库：未登录用户不可能有点赞记录。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>userId 为 null（游客）直接返回 false；</li>
     *     <li>按「post_id + user_id」统计条数，大于 0 即视为已赞。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用的是「查条数」而不是「查整行」，因为只关心有没有；
     * 另外 count 的返回值是包装类型，必须判空再比较，否则脏数据下会 NPE。
     *
     * @param postId 帖子 ID
     * @param userId 用户 ID；为 null（游客）时直接返回 false，不查库
     * @return 已赞返回 true；游客、未赞或统计结果为空返回 false
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
     *
     * <p>为什么这样设计：分页参数来自前端，传 0、传负数、传一个巨大的 pageSize
     * 都是常见的。对前两种，抛异常让整个列表打不开并不划算，纠正后继续查更友好；
     * 对第三种必须设上限——不设上限时一个 pageSize=100000 就能把整表读进内存。
     * 这与「参数非法就抛 BusinessException」的总体约定是刻意的不一致，
     * 理由就是「页码错了没必要让查询失败」。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>页码小于 1 时按 1；</li>
     *     <li>每页条数小于 1 时按默认值；</li>
     *     <li>再把每页条数收敛到上限。</li>
     * </ol>
     *
     * <p>容易踩的坑：默认值与上限的收敛顺序不能反——先取 min 再判小于 1，
     * 会让传入 0 时得到 0 条，查询直接返回空页。
     *
     * @param current 原始页码
     * @param pageSize 原始每页条数
     * @return 纠正后的分页对象
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
     * <p>为什么这样设计：「我的发帖」「待审队列」「管理端列表」三条路径共用——
     * PostVO 契约上 authorName 恒有值，而不是「有的接口填、有的不填」，
     * 前端不必为每个接口写不同的兜底。取名统一走 adminAuthorNames，
     * 因为它带账号昵称兜底，老帖子的作者不会显示成空白。
     *
     * <p>执行步骤（与代码里的顺序一一对应）：
     * <ol>
     *     <li>取出本页记录，一次查出所有作者的展示名；</li>
     *     <li>先建空 VO 分页保留 total/current/size；</li>
     *     <li>逐条拷贝属性、补 authorName，再塞回分页对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「统一」改用 authorNames（只查在队成员）——
     * 那样离队成员的历史帖子在管理端会显示成空作者，管理员无法核实是谁发的。
     *
     * @param entityPage 实体分页，可为空记录
     * @return 同 total/current/size 的 VO 分页
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
     * <p>为什么这样设计：与 authorNames 分开，因为口径不同——那个只认「在队成员」的姓名
     * （门户的对外口径）；而帖子不会因为成员毕业离队而消失，管理端要看到所有发过帖的账号，
     * 只查在队成员会让老帖子的作者变成空白。
     * 口径又与门户保持一致：在队成员优先用成员姓名（否则同一篇帖子在门户显示
     * 「陈星野」、在后台显示账号名，管理员会以为数据出了问题）；查不到成员档案时
     * 兜底账号昵称，再兜底账号名——显示总比留空好。
     *
     * <p>执行步骤（与代码里的 ①② 标注一一对应）：
     * <ol>
     *     <li>先按在队成员取名（与门户同一口径）；</li>
     *     <li>把剩下没取到名的 ID 去重后，一次查出账号信息并用昵称兜底。</li>
     * </ol>
     *
     * <p>容易踩的坑：只对「缺名字的那部分」查账号，不要每次都全量查——
     * 绝大多数帖子作者都在队，全量查等于多一次没有必要的 SQL。
     * 另外昵称与账号名都可能为空，两个都空时宁可不放进映射，也不要塞空串。
     *
     * @param userIds 作者 ID 集合，可为 null 或空
     * @return userId → 展示名（可能没有值，调用方按 null 处理）；入参为空时返回空映射
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

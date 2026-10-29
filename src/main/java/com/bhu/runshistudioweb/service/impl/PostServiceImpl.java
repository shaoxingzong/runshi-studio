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
 * 依赖都走构造器注入（final 字段 + {@code @RequiredArgsConstructor}）。
 *
 * 注入 Mapper 而非对应 Service，是为了批量查询能力（按一批 user_id 反查成员姓名等），
 * 保证一次查询而不是 N+1；Service 之间互相依赖也容易成环，启动即失败。
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
     * 正文会在入库与返回详情时各传输一次，几十 MB 的输入足以拖垮两端。
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
     * 只捕获地址。地址里不含空格与右括号，这个简化正则够用，不必引入完整解析器。
     */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    /** 站内图片路径前缀：只允许引用我们自己上传目录里的图片 */
    private static final String INTERNAL_IMAGE_PREFIX = "/uploads/";

    private final StudioMemberService studioMemberService;

    private final StudioMemberMapper studioMemberMapper;

    /**
     * 内容审核（AI 三分法）
     *
     * 只负责「判定」，落库由本类完成——依赖方向必须单向，否则循环依赖启动即失败。
     */
    private final ContentAuditManager contentAuditManager;

    /**
     * 点赞关系（studio_post_like）
     *
     * 注入 Mapper：只需「查重」与「按 post_id 批量查已赞集合」两种单表查询，
     * 批量能力走 Mapper 最直接，能保证一次查询而不是 N+1。
     */
    private final StudioPostLikeMapper studioPostLikeMapper;

    /**
     * 评论（studio_post_comment）
     *
     * 注入 Mapper 而非 Service：删帖要按 post_id 批量逻辑删除该帖全部评论，
     * 这是单表批量能力；Service 互相依赖也容易成环。
     */
    private final StudioPostCommentMapper studioPostCommentMapper;

    /**
     * 站内通知（审核结论送达作者）
     *
     * 依赖方向单向：通知服务只做投递，不认识帖子；标题正文由本类决定，
     * 幂等与失败不拖垮等策略集中在 {@code NotificationService}。
     */
    private final NotificationService notificationService;

    /**
     * 操作日志（审核 / 删除 / 置顶 / 代改都要留痕）
     *
     * 写在这里而不是 Controller：只有 Service 知道「实际处理了哪几篇、驳回理由是什么」，
     * 切面拿到的只有一串 ID，事后依然回答不了「谁对哪条内容做了什么」。
     */
    private final OperationLogService operationLogService;

    /**
     * 举报表 Mapper
     *
     * 删帖会连带删评论，被删内容上的待处理举报必须同事务关闭——否则它们永远留在
     * 待处理队列里，点开却只剩「内容已删除」，管理员既处置不了也关不掉。
     * 注入 Mapper 而不是 ReportService：ReportService 依赖本服务（删除动作），反向注入会成环。
     */
    private final StudioPostReportMapper studioPostReportMapper;

    /**
     * 用户（sys_user）
     *
     * 管理端要按作者关键词筛选、展示作者昵称，都是「按一批 id / 一个模糊词」的批量查询。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * 发布帖子（先审后发）
     *
     * 落库一律写「待审」，能否公开交给 AI 初判与人工审核；若这里写「已通过」，
     * 审核环节就形同虚设。准入只认 studio_member 的在队档案，不认角色标签——
     * 角色可能与被移出名录不同步，按角色放行会绕开真正的准入规则。
     *
     * 坑：提交成功只代表进了审核队列，新帖在公开列表查不到是设计如此。
     * 异步回调里只能用请求线程提前取好的变量，那里拿不到登录态。
     *
     * @param request 发布请求（标题、正文必填；摘要与封面为空时兜底生成），不能为 null
     * @return 新帖子的雪花 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 非在队成员（A0301）、正文过长或含外链图片（A0401）、入库失败（B0001）
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
     * 更新条件带两个 eq，缺一不可：{@code 未判} 保证幂等，「待审」保证人工已处置的
     * 内容不被后到的机器结论改写——人的决定优先于机器。audit_by 留空，AI 不是人。
     *
     * 坑：不要在这里抛异常。它跑在异步线程上，抛出去只会被线程池吞掉，内容照样停在待审。
     *
     * @param postId 帖子 ID，由调用方在提交时固定好（回调里取不到登录态）
     * @param result AI 结论，非空——{@code AuditResult} 的紧凑构造器会把 null 兜成「转人工」
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
     * 先确认帖子可赞、再查重、最后才写，顺序反了会给不存在的帖子点赞。
     * 计数用 SQL 表达式自增，不用「读出来 +1 再写回」——后者并发下会互相覆盖。
     *
     * 坑：并发下两个请求可能同时通过查重，后插入的撞唯一键 uk_post_user。
     * 这不是错误，语义上等同「已经赞过」，必须 catch 住按幂等返回。
     *
     * @param userId 登录用户 ID（由 Controller 从登录态取）
     * @param postId 被点赞的帖子 ID，必须大于 0，否则抛 A0401
     * @return 点赞后的最新点赞数（已赞过时原样返回）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或未通过审核（A0402）、
     * 写入失败（B0002）
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
     * 只返回「已通过」，且条件必须写进 SQL 的 WHERE——查出来再在内存里过滤会让
     * 分页总数把未过审内容也算进去。排序是置顶优先、再按创建时间倒序。
     *
     * 作者信息与「我赞过没」必须各一次批量查询补全，逐条查就是 N+1。
     * 坑：{@code BeanUtils.copyProperties} 只拷两边都有的属性，authorName 与 liked
     * 源实体里没有，漏补任一个前端都会显示空白。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @param keyword 搜索关键词（可选）：同时匹配标题与摘要，为空表示不过滤
     * @return 分页结果；没有已通过的帖子时返回空记录分页，不会返回 null
     */
    @Override
    public Page<PostFrontVO> listApprovedByPage(long current, long pageSize, String keyword) {
        String trimmedKeyword = StrUtil.trimToNull(keyword);
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.APPROVED.getValue())
                // 关键词必须进 WHERE：查出来再在内存里过滤会让分页总数算错
                // （「共 10 条却只显示 3 条」），也会让翻页结果不稳定
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

    /**
     * 帖子详情（游客可访问），并累加浏览量
     *
     * 浏览量用 SQL 表达式自增，不用「读出来 +1 再写回」——后者并发下互相覆盖，
     * 而浏览量是内容热度的依据，被低估很难纠正。查询带 id 与「已通过」两个条件：
     * 待审 / 已驳回对公众等同于不存在，不能给出「存在但没过审」的信息。
     *
     * 坑：游客也要能看详情，所以「是否赞过」必须允许当前用户为空。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含 Markdown 正文）；作者名在成员档案缺失时为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核（A0402）
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
        // 浏览量要按「自增后」返回：post 是自增之前查出来的快照，直接拷贝会让详情页显示的数字
        // 比库里少 1（用户刷新一次才追平）。这里直接 +1 而不是再查一次：
        // 重新查询也多一次往返，且并发下读到的仍可能是别人自增后的值，口径没有更准
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

    /**
     * 我的发帖：含待审与已驳回，供作者跟进审核状态
     *
     * 先审后发必须配这个出口：作者提交后在公开列表看不到自己，必须能查到状态与驳回理由。
     * 因此刻意不加状态过滤，只按 author_id 限定。
     *
     * 坑：不要为复用直接套用公开列表的查询条件，那里带 status = 已通过，会把待审稿全滤掉。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果（含状态、驳回理由、AI 初判结论）
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
     * 按创建时间升序：审核是排队作业，先提交先处理，否则老内容会被新内容压到队尾——
     * 在「先审后发」下，沉底等于被静默丢弃。
     *
     * 坑：本方法不校验登录态与管理员角色，那两道在 Controller 的 {@code @SaCheckRole}。
     * 队列里是全站待审内容，暴露给普通用户就是一次内容泄露。
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
     * 一条 UPDATE 处理整批，逐条更新会把「勾选 20 条一次通过」变成 20 次往返。
     * 更新条件必须带「状态 = 待审」：已处理的记录不该被覆盖，这同时是幂等保护。
     *
     * 坑：处理条数要用 {@code baseMapper.update} 的受影响行数，{@code this.update} 只返回
     * 成功与否；查通知名单与执行更新必须用完全相同的条件，否则会出现「通知发了、实际没改」。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、驳回需理由）
     * @return 实际处理的条数（已被处理过的不计入）
     * @throws BusinessException 未登录（A0201）、动作非法或驳回缺理由（A0401）
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
                .set(StudioPost::getAuditAt, LocalDateTime.now())
                // Wrapper 更新不传实体，MyMetaObjectHandler.updateFill 不会触发，
                // 审计列必须在这里显式写；否则审核完 updated_by 还停在上一个操作人
                .set(StudioPost::getUpdatedBy, auditorId)
                .set(StudioPost::getUpdatedAt, LocalDateTime.now());
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

        // 留痕：一篇一行。事故排查的入口通常是「这篇帖子被谁驳回了」，
        // 合并成一行就只能全文搜索 detail；rows 为 0 时不记——没有生效的处置不该留下痕迹
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

    // ==================== 管理端：全量列表 / 删除 / 置顶 / 编辑 ====================

    /**
     * 管理端帖子列表（全状态，支持状态 / 标题 / 作者筛选）
     *
     * 作者关键词先解析成一批 userId 再按 author_id IN 过滤，不写 JOIN：
     * 为一个模糊筛选维护手写 SQL 不值得，sys_user 量级很小。三个筛选条件彼此独立，
     * 任一为空即「不按它筛」，而不是「查该字段为空的行」。
     *
     * 坑：分页字段是包装类型 Long，GET 的 {@code ?current=} 传空串会绑成 null，
     * 直接拆箱会 NPE，必须先判空再取默认值。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页），不能为 null
     * @return 分页结果（含作者展示名）；筛选不到内容时返回空记录分页
     * @throws com.bhu.runshistudioweb.exception.BusinessException 查询参数为 null、
     * 审核状态取值非法（A0401）
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
            // 标题模糊匹配：占位符传参，不存在 SQL 注入；% 与 _ 仍是 LIKE 通配符，
            // 安全结论见 HELP.md 第 7 节（本次只记录风险，不改变通配符语义）
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
     * 账号与成员姓名都要匹配：门户展示用的是成员姓名，管理员照着屏幕上的名字搜必须能搜到。
     *
     * 坑：返回值有三种语义——null 表示「不按作者筛选」，空集合表示「匹配不到任何人」，
     * 非空集合才是真正的筛选条件；混为一谈会让「不筛选」变成「查不到任何东西」。
     * 成员档案的 user_id 允许为空，必须跳过，否则 null 会被拼进 IN 条件。
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
     * 逻辑删除保住审计线索；但只删帖子会留下两类孤儿——评论还占着审核队列、
     * 点赞关系挂在已不存在的帖子上，所以三处写必须在同一事务里完成。
     * 顺序上最后删帖子本体，否则中途失败会留下「父帖已删、子行还在」。
     *
     * 坑：待审帖子不能删。先审后发之下作者只知道「我提交了」，直接删掉他什么都收不到，
     * 该走驳回把理由送到作者眼前。本表没有 deleted_by 列，审计痕迹要自己写 updated_by
     * （Wrapper 更新不传实体，元数据填充器不会触发）。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、尚在待审（A0401）
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
        //    不能指望 MyMetaObjectHandler：Wrapper 更新没传实体，更新填充器根本不会触发
        this.update(new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .set(StudioPost::getUpdatedBy, operatorId));

        // ② 先取评论 ID：删完之后再查就查不到了，而这些 ID 正是关闭评论举报的依据
        List<Long> commentIds = studioPostCommentMapper.selectList(new LambdaQueryWrapper<StudioPostComment>()
                        .select(StudioPostComment::getId)
                        .eq(StudioPostComment::getPostId, postId))
                .stream()
                .map(StudioPostComment::getId)
                .toList();

        // ③ 级联删评论（含楼中楼回复）：只删帖子会留下「父帖没了却还占审核队列」的孤儿
        //    this.remove 只返回 boolean，要实际条数故走 baseMapper.delete（MP 会改写成逻辑删除）
        int commentRows = studioPostCommentMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId));

        // ④ 级联删点赞关系：studio_post_like 是关系表、物理删除（见 DDL 注释），
        //    留着会变成挂在不存在帖子上的悬空行
        studioPostLikeMapper.delete(new LambdaQueryWrapper<StudioPostLike>()
                .eq(StudioPostLike::getPostId, postId));

        // ⑤ 删帖子本体
        baseMapper.delete(new LambdaQueryWrapper<StudioPost>().eq(StudioPost::getId, postId));

        // ⑥ 同事务关闭被删内容上的待处理举报：帖子与它名下全部评论都要关，
        //    否则这些举报永远留在队列里，点开只剩「内容已删除」
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

    /**
     * 关闭一批目标上的待处理举报（内容已被删除）
     *
     * 只更新仍处于「待处理」的记录：已经处置过（受理/不成立）的举报保留原结论，
     * 不能被这次删除改写。审计列必须显式写——Wrapper 更新不触发填充器。
     *
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表；空列表直接返回
     * @param operatorId 操作人（删除内容的管理员）
     */
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

    /**
     * 置顶 / 取消置顶
     *
     * 只有「已通过」才能置顶：公开列表只返回已通过内容，置顶待审帖在门户上没有任何效果。
     * 取消置顶刻意不设状态限制——帖子被编辑后会重置为待审但 pinned 仍为 1，
     * 若不许取消，管理员就卡在「改不回来」的状态里。
     *
     * 坑：status 列理论上可能为空，比较前要先判 null，直接拆箱会 NPE。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、取值非法或对非已通过帖子置顶（A0401）、更新失败（B0002）
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

    /**
     * 编辑帖子（仅管理员），非待审状态一律重置为待审重新送审
     *
     * 编辑权只给管理员：作者能自由改已通过的正文，等于给审核开后门。编辑后必须重置待审，
     * 并清掉上一轮的审核痕迹（AI 结论、审核人、审核时间、驳回理由）——它们描述的是
     * 修改前那份内容，留着会让管理员误以为新内容已经判过。
     *
     * 坑：本方法不校验管理员角色，那道在 Controller 的 {@code @SaCheckRole(ADMIN)}。
     *
     * @param request 编辑请求（id、标题、正文必填；摘要与封面为空时兜底生成），不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、正文过长或含外链图片（A0401）、保存失败（B0002）
     */
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

    /**
     * 作者修改自己被驳回的帖子（改完重新送审）
     *
     * 与管理员代改共用同一套改稿实现，差别只在准入：必须是作者本人，且状态为已驳回。
     * 待审帖不许作者改——它正在队列里，反复改会让审核对象不断变化；已通过的更不允许，
     * 那是公开内容。其余情况一律按「帖子不存在」返回，不暴露存在性。
     *
     * 坑：两个校验的先后不能换。先判状态再判本人，外人能靠提示语差异反推帖子状态。
     *
     * @param request 改稿请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是已驳回（均 A0402）、正文过长或含外链图片（A0401）、
     * 保存失败（B0002）
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

        applyPostEdit(post, request, userId);
        log.info("作者修改被驳回帖子 | postId={} | authorId={}", post.getId(), userId);
    }

    /**
     * 改稿的公共实现（管理员代改与作者自助改共用）
     *
     * 两条入口规则完全一致，复制一份意味着将来改规则要改两处，漏改会造成
     * 「管理员改的会重新送审、作者改的不会」这类难查差异。调用方负责准入，本方法只管改稿。
     *
     * 坑：编辑待审帖同样要重送 AI 判——它可能已带一轮针对旧正文的结论，不重送则新正文
     * 永远不会被机器过一遍。清空痕迹必须用 {@code set(..., null)} 显式写入，不能指望
     * 「不传就不改」，那样旧结论会原样留着。
     *
     * @param post 已取出的帖子实体，调用方保证非空且有权限
     * @param request 改稿请求（标题、正文必填；摘要与封面选填）
     * @param operatorId 本次改稿人 ID（管理员代改是管理员，作者自助改是作者本人）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 正文过长或含外链图片（A0401）、
     * 保存失败（B0002）
     */
    private void applyPostEdit(StudioPost post, PostUpdateRequest request, long operatorId) {
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
                .set(StudioPost::getContent, content)
                // 内容变了：审计列必须显式写（Wrapper 更新不传实体，填充器不会触发）
                .set(StudioPost::getUpdatedBy, operatorId)
                .set(StudioPost::getUpdatedAt, LocalDateTime.now())
                // AI 三列**无条件**清空：待审帖同样要重送 AI 判，旧结论描述的是旧正文，
                // 留着会让审核页挂着「AI 判安全」而实际内容已经改过。
                // 必须显式 set(..., null)：null 字段会被「不传就不改」的更新语义跳过，旧结论原样留存
                .set(StudioPost::getAiAuditStatus, AiAuditStatusEnum.NOT_JUDGED.getValue())
                .set(StudioPost::getAiReason, null)
                .set(StudioPost::getAiAt, null);
        if (backToPending) {
            wrapper.set(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                    // 人工审核痕迹只在「打回待审」时作废：待审帖本来就没有审核人，
                    // 去 set 它们只会把并发的审核结果覆盖成 null
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
     * 公开详情既不覆盖待审 / 已驳回，又会原子自增浏览量——管理员点开编辑一下就 +1，
     * 而浏览量是热度依据，不能被管理动作污染，所以管理端要有自己的取详情路径。
     * Service 内也校验登录态，不把「调用方一定鉴权过」当前提。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文），三种审核状态都会返回；作者展示名查不到时为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）
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
     * 改稿的前置步骤：驳回稿的正文作者本来读不到（公开详情只返回已通过），要改就得先看到。
     * 已通过的走公开详情即可，这里刻意不覆盖。非本人一律按「不存在」返回。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是待审或已驳回（均 A0402）
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
     * 必须在服务端校验：编辑器只提供上传入口，用户完全可以手改 Markdown 塞外链。
     * 外链图会成为追踪像素（访客打开页面就泄露 IP），对方删图后还会裂图。
     *
     * 坑：正则只认标准 Markdown 图片语法且地址不含空格，这是刻意接受的简化。
     *
     * @param content 已清洗过的正文
     * @throws com.bhu.runshistudioweb.exception.BusinessException 存在非站内图片地址（A0401）
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
     * 只处理最常见的几种标记，目的是摘要里不出现裸语法，而不是完整解析 Markdown。
     * 摘要在入库时算好落库，读取时不再现算，否则列表页一页 20 条就是 20 次字符串处理。
     *
     * 坑：替换顺序不能调换。若先删强调符号再处理链接，链接语法会先被破坏，后面就匹配不上。
     *
     * @param content 已清洗过的正文，不应为 null（调用方已做过 trim）
     * @return 纯文本摘要，最长 {@code SUMMARY_FALLBACK_LENGTH} 个字；正文全是标记时可能返回空串
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
     * 封面为空时用正文首图兜底，用户不填也有列表缩略图。只取第一张：列表页只要一张，
     * 多取反而要多解析一遍全文。
     *
     * 坑：返回 null 是正常结果（正文无图），不要改成空串——前端对两者的处理往往不同。
     *
     * @param content 已清洗过的正文
     * @return 图片地址；正文无图时返回 null（列表页按「无封面」渲染）
     */
    private String extractFirstImage(String content) {
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 批量查作者信息（姓名 + 头像，一次查询，避免 N+1）
     *
     * 逐条查询一页 10 条就是 10 次额外查询。姓名与头像来自同一条 SQL 的同一批行，
     * 必须一次取回，不能「查完名字再查一次头像」——那等于把 N+1 换成 2N。
     * 只认在队成员：这是门户的对外口径（管理端另有 {@code adminAuthorNames} 兜底）。
     *
     * 坑：返回的映射可能缺少某些 userId（作者已离队或没绑档案），调用方取值后必须允许 null；
     * 头像本身也可能为空，前端用姓名首字兜底，不属于异常。
     *
     * @param userIds 作者 ID 集合，可为 null 或空
     * @return userId → 作者档案（姓名必有值，头像可能为 null）；入参为空时返回空映射
     */
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

    /**
     * 作者的展示信息（姓名 + 头像）
     *
     * 用不可变载体而不是两个并列 Map：两者必须来自同一条档案记录，
     * 拆开会让调用方取到「A 的姓名 + B 的头像」。
     */
    private record AuthorProfile(String name, String avatar) {
    }

    /**
     * 当前登录用户 ID（未登录返回 null）
     *
     * 游客的 liked 恒为 false，未登录时不查库、直接返回 null；公开详情本就允许游客访问，
     * 未登录不是错误，所以不抛异常。
     *
     * 坑：调用方拿到 null 必须当「游客」处理，不能直接拆箱成 long，那会 NPE。
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
     * 逐条查询一页 10 条就是 10 次额外查询；游客不查库，直接返回空集合。
     *
     * 坑：条件里必须同时带 user_id，否则会把所有人的点赞都算成当前用户赞过。
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
     * 两处语义相同，共用同一方法；复制一份将来改口径就要改两处，漏改会出现
     * 「详情显示已赞、点赞却还能再赞一次」。游客直接返回 false 而不查库。
     *
     * 坑：{@code count} 的返回值是包装类型，必须判空再比较，否则脏数据下会 NPE。
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
     * 这是对「参数非法就抛异常」总体约定的刻意例外。每页条数必须有上限，
     * 否则一个 {@code pageSize=100000} 就能把整表读进内存。
     *
     * 坑：默认值与上限的收敛顺序不能反——先取 min 再判小于 1，传入 0 会得到 0 条，直接空页。
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
     * 「我的发帖」「待审队列」「管理端列表」三条路径共用：{@code PostVO} 契约上
     * authorName 恒有值，前端不必为每个接口写不同的兜底。取名统一走
     * {@code adminAuthorNames}，它带账号昵称兜底，老帖子的作者不会显示成空白。
     *
     * 坑：不要为了统一改用只查在队成员的取法，那样离队成员的历史帖子会显示成空作者。
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
     * 与 {@code authorProfiles} 口径不同：那个只认在队成员（门户对外口径），
     * 而帖子不会因成员离队而消失，管理端要看到所有发过帖的账号。在队成员优先用成员姓名
     * （与门户一致，否则同一篇帖子两处显示不同名字），查不到档案时兜底账号昵称、再兜底账号名。
     *
     * 坑：只对「缺名字的那部分」查账号，不要每次全量查。昵称与账号名都为空时宁可不放进映射，
     * 也不要塞空串。
     *
     * @param userIds 作者 ID 集合，可为 null 或空
     * @return userId → 展示名（可能没有值，调用方按 null 处理）；入参为空时返回空映射
     */
    private Map<Long, String> adminAuthorNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // ① 先按在队成员取名（与门户同一口径）
        Map<Long, String> names = new HashMap<>();
        authorProfiles(userIds).forEach((userId, profile) -> names.put(userId, profile.name()));

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

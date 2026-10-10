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
import com.bhu.runshistudioweb.mapper.StudioPostMapper;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.AuditResult;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.AuditStatusEnum;
import com.bhu.runshistudioweb.model.enums.MemberStatusEnum;
import com.bhu.runshistudioweb.model.vo.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.PostVO;
import com.bhu.runshistudioweb.service.PostService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // ① 图片必须是站内路径：外链图会成为追踪像素（泄露访客 IP），也可能将来裂图
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

        Page<PostFrontVO> voPage = emptyVOPage(entityPage);
        List<PostFrontVO> records = new ArrayList<>(entityPage.getRecords().size());
        for (StudioPost post : entityPage.getRecords()) {
            PostFrontVO vo = new PostFrontVO();
            BeanUtils.copyProperties(post, vo);
            vo.setAuthorName(names.get(post.getAuthorId()));
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
        Page<PostVO> voPage = emptyVOPage(entityPage);
        voPage.setRecords(entityPage.getRecords().stream()
                .map(post -> {
                    PostVO vo = new PostVO();
                    BeanUtils.copyProperties(post, vo);
                    return vo;
                })
                .toList());
        return voPage;
    }

    @Override
    public Page<PostVO> listPendingByPage(long current, long pageSize) {
        LambdaQueryWrapper<StudioPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPost::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPost::getCreatedAt);

        Page<StudioPost> entityPage = this.page(newPageWithDefaults(current, pageSize), wrapper);
        Page<PostVO> voPage = emptyVOPage(entityPage);
        voPage.setRecords(entityPage.getRecords().stream()
                .map(post -> {
                    PostVO vo = new PostVO();
                    BeanUtils.copyProperties(post, vo);
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
}

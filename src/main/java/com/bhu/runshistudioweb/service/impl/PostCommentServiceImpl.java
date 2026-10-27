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
import com.bhu.runshistudioweb.model.enums.NotificationTypeEnum;
import com.bhu.runshistudioweb.model.vo.post.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.post.PostCommentVO;
import com.bhu.runshistudioweb.service.NotificationService;
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
 * 帖子评论服务实现（楼中楼，先审后发）
 *
 * 两条贯穿全类的规则：楼中楼只支持两层，回复的回复仍挂在同一个顶层评论下，
 * 列表组装因此只要「一次查询 + 内存分组」，不必递归；评论提交后是待审，
 * 帖子的 comment_count 只在真正公开那一刻累加，保证「评论数」与「点开能看到的评论」一致。
 *
 * 坑：列表必须一次查回整帖评论再按 parentId 挂树，绝不能「先查顶层再逐条查回复」，
 * 那是标准 N+1。审核判定走 {@code ContentAuditManager}、通知投递走
 * {@code NotificationService}，两者都不认识评论表，依赖方向保持单向。
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

    /**
     * 分页每页条数的默认值与上限
     *
     * 上限防「一次拉全表」：待审队列是全站数据，不设上限时一个 pageSize=100000 就能读进内存。
     */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /**
     * 帖子表 Mapper
     *
     * 本类只做两件单表操作：确认归属帖子「存在且已通过」、原子增减 comment_count。
     * 不注入 {@code PostService}：{@code PostServiceImpl} 已经依赖评论表，会成环。
     */
    private final StudioPostMapper studioPostMapper;

    /**
     * 成员档案 Mapper
     *
     * 列表要显示评论人姓名，需要「按一批 user_id 反查」的批量能力，一次 IN 查询避免 N+1。
     */
    private final StudioMemberMapper studioMemberMapper;

    /**
     * 内容审核（AI 三分法）
     *
     * 与帖子共用同一个 Manager：判定逻辑不区分内容类型，差别只在结论落到哪张表。
     * 只让它判定、落库由本类完成，保持依赖方向单向，否则循环依赖启动即失败。
     */
    private final ContentAuditManager contentAuditManager;

    /**
     * 站内通知（评论通过后告知被回复者）
     *
     * 依赖方向单向：通知服务不认识评论，标题正文由本类决定；幂等、不给自己发、
     * 失败不拖垮主业务等策略集中在 {@code NotificationService}。
     */
    private final NotificationService notificationService;

    /**
     * 发表评论，或回复某条顶层评论（先审后发）
     *
     * 沿用「先审后发」但准入门槛放宽到「登录即可」：发帖代表工作室对外发声，
     * 评论只是读者交流，若也要求成员身份就等于关掉访客的参与渠道。楼层号由服务端分配，
     * 前端传会立刻出现重复与跳号。
     *
     * 坑：本方法刻意不累加 comment_count——评论还是待审，计数会显示「有 1 条」却点开为空。
     *
     * @param request 评论请求：postId 与 content 必填（content 最长 1000 字符）；
     * parentId 选填，不传表示直接评论帖子（有楼层号），传了表示回复顶层评论
     * @return 新评论的雪花 ID
     * @throws BusinessException 请求体为空或父评论不合法（A0401）、未登录（A0201）、
     * 帖子不存在或未过审（A0402）、入库失败（B0001）
     */
    @Override
    public long addComment(PostCommentAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "评论内容不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();

        // ① 帖子必须存在且已通过：对待审/已驳回的帖子评论没有意义，也避免绕过审核往未公开内容上挂评论。
        //    注意 getValue() 返回的是 int，不能对它调 equals；且 status 理论上可能为 null，要先判空
        StudioPost post = studioPostMapper.selectById(request.getPostId());
        ThrowUtils.throwIf(post == null
                        || post.getStatus() == null
                        || post.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.NOT_FOUND_ERROR, "帖子不存在或尚未通过审核");

        // 正文 trim 后入库：首尾空格在楼层里看不出，却会占掉 1000 字的额度
        String content = StrUtil.trim(request.getContent());

        // ② 回复的合法性：父评论必须是同一帖子下的顶层评论（只支持两层楼中楼）。
        //    这一步必须在组装实体之前：父评论不合法时，楼层号已经被算掉了
        Long parentId = request.getParentId();
        if (parentId != null) {
            StudioPostComment parent = this.getById(parentId);
            // 三个条件缺一不可：不存在、跨帖子、父评论本身已是回复（parent.getParentId() 非空），
            // 任一成立都拒绝——第三个条件正是「只支持两层」的落点
            ThrowUtils.throwIf(parent == null
                            || !parent.getPostId().equals(request.getPostId())
                            || parent.getParentId() != null,
                    ErrorCode.PARAMS_ERROR, "只能回复帖子下的顶层评论");
        }

        // ③ 组装实体：状态固定写待审，不读请求体里的任何状态字段（先审后发）
        StudioPostComment comment = new StudioPostComment();
        comment.setPostId(request.getPostId());
        comment.setAuthorId(userId);
        comment.setContent(content);
        comment.setParentId(parentId);
        comment.setStatus(AuditStatusEnum.PENDING.getValue());
        // 只有顶层才有楼层号；回复依附于父评论，编号会破坏"第几楼"的语义
        comment.setFloor(parentId == null ? nextFloor(request.getPostId()) : null);

        // ④ 落库。save 只返回 boolean，失败时给 B0001（系统异常），而不是静默返回 0
        boolean saved = this.save(comment);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "评论失败，数据库异常");

        // 这里不累加 post.comment_count：
        // 评论是待审状态，此时计数会让用户看到"有 1 条评论"却点开什么都没有；
        // 计数只在「真正公开」这一刻累加——人工通过走审核接口，AI 判安全走 applyAiVerdict
        log.info("评论已提交待审 | commentId={} | postId={} | authorId={}",
                comment.getId(), request.getPostId(), userId);

        // ⑤ 异步送 AI 初判（不阻塞提交）。闭包里只带 commentId 与正文：
        //    回调跑在虚拟线程上，那里取不到 RequestContextHolder，
        //    所以 id 必须先在请求线程里取出来
        long newCommentId = comment.getId();
        contentAuditManager.submitAsync(content, result -> applyAiVerdict(newCommentId, result));
        return comment.getId();
    }

    /**
     * 把 AI 的结论写回评论（三分法处置）
     *
     * 与帖子版同一套规则：两个 eq 缺一不可——{@code 未判} 保证幂等，「待审」保证
     * 人工结论优先于机器。audit_by 留空，AI 不是人。差别在于判安全后要顺带维护
     * 帖子的 comment_count 并通知被回复者，这两件事只能在评论真正公开这一刻做。
     *
     * 坑：不要在这里抛异常。它跑在异步线程上，抛出去只会被线程池吞掉，评论照样停在待审。
     *
     * @param commentId 评论 ID，由调用方在提交时固定好（回调里取不到请求上下文）
     * @param result AI 结论，非空——{@code AuditResult} 的紧凑构造器会把 null 兜成「转人工」
     */
    private void applyAiVerdict(long commentId, AuditResult result) {
        // ① 先取评论：通过后要拿 postId 去维护帖子的评论数，这是 update 拿不到的。
        //    取不到说明评论已被删除（或并发删除），此时什么都不该做
        StudioPostComment comment = this.getById(commentId);
        if (comment == null) {
            return;
        }

        AiAuditStatusEnum aiStatus = result.status();
        LocalDateTime now = LocalDateTime.now();

        // ② 两个 eq 条件缺一不可：「待审」保证人工结论优先，「未判」保证幂等
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

        // ③ 用 baseMapper.update 拿影响行数：0 行不是错误，而是「已经被处理过」
        int rows = baseMapper.update(null, wrapper);
        if (rows == 0) {
            log.debug("AI 结论未落库（已被人工处置或已判过）| commentId={} | 结论={}",
                    commentId, aiStatus.getDesc());
            return;
        }

        // ④ 只有「判安全」才真正公开，此时才维护评论数与发通知
        if (aiStatus == AiAuditStatusEnum.SAFE) {
            // 与人工通过走同一套增量维护：评论数在「真正公开」这一刻才 +1，
            // 这样「评论数」与「点开能看到的评论」始终一致。
            // 拼接的是固定数字 1，不是用户输入；用 SQL 表达式避免读改写竞态
            studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                    .eq(StudioPost::getId, comment.getPostId())
                    .setSql("comment_count = comment_count + 1"));
            // 回复通知：评论「真正可见」这一刻才告知被回复者。
            // 提交时不通知——那时评论还是待审，被回复者点开看不到内容
            notifyReplyIfParent(comment);
        }
        log.info("AI 审核完成 | commentId={} | 结论={}", commentId, aiStatus.getDesc());
    }

    /**
     * 评论通过后，通知被回复者（父评论的作者）
     *
     * 通知挂在「通过」而不是「提交」这一刻：提交时内容对世界还不可见，提前通知会让
     * 被回复者点开帖子却找不到那条回复。人工通过与 AI 判安全共用本方法，避免两条路径
     * 的逻辑漂移。幂等键用 postId 而非 commentId：同一帖子下的多次回复聚合成一条，
     * 既避免刷屏，也让唯一键语义与产品语义一致。
     *
     * 坑：父评论的作者可能正是当前评论的作者（自己回复自己），这里不做判断——
     * 「不给自己发」的规则集中在 {@code NotificationService}，分散到调用点迟早会漏。
     *
     * @param comment 已通过审核的评论，调用方保证非空；parentId 为 null 时直接返回
     */
    private void notifyReplyIfParent(StudioPostComment comment) {
        // ① 顶层评论没有被回复者，不需要通知
        if (comment.getParentId() == null) {
            return;
        }
        // ② 父评论可能已被删：悬空场景跳过通知，不影响评论本身
        StudioPostComment parent = this.getById(comment.getParentId());
        if (parent == null) {
            return;
        }
        // ③ 帖子可能也已被删（级联删除的中间态）：标题取不到时用固定文案兜底，
        //    避免把 null 拼进用户能看到的通知正文里
        StudioPost post = studioPostMapper.selectById(comment.getPostId());
        String postTitle = post == null ? "原帖子" : post.getTitle();
        notificationService.notify(parent.getAuthorId(), comment.getAuthorId(),
                NotificationTypeEnum.COMMENT_REPLIED.getValue(), "POST", comment.getPostId(),
                "有人回复了你的评论",
                "你的评论在《" + postTitle + "》下收到了新回复");
    }

    /**
     * 某帖子的公开评论列表（游客可调用，楼中楼已组装好）
     *
     * 一次查询把该帖全部已通过的评论取回，再在内存里按 parentId 分组挂到顶层评论下。
     * 只支持两层让组装不必递归。排序按 id 升序：雪花 ID 天然反映创建先后，
     * 同一毫秒内创建的评论也有确定顺序。
     *
     * 坑：不要对每条顶层评论各过滤一遍全量列表，那等于在内存里重演 N+1；
     * 「已通过」条件必须写进 SQL 的 WHERE，而不是查出来再在内存里过滤。
     *
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 顶层评论列表（每条自带 children，没有回复时为空列表）；无已通过评论时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子 id 不合法（A0401）
     */
    @Override
    public List<PostCommentVO> listApprovedByPost(long postId) {
        ThrowUtils.throwIf(postId <= 0, ErrorCode.PARAMS_ERROR, "帖子 id 不合法");

        // ① 一次查完：某帖的全部已通过评论，按 id 升序让结果天然按时间有序（雪花 ID 递增）
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

        // ② 作者信息（姓名 + 头像）一次查完（批量，避免逐条查的 N+1）
        Map<Long, AuthorProfile> profiles = authorProfiles(all.stream()
                .map(StudioPostComment::getAuthorId).toList());

        // ③ 内存挂树：顶层单独收集，回复按 parentId 暂存。
        //    一次遍历边分组，不要对每条顶层再过滤一遍全量列表
        List<PostCommentVO> tops = new ArrayList<>();
        Map<Long, List<PostCommentVO>> repliesByParent = new HashMap<>();
        for (StudioPostComment comment : all) {
            PostCommentVO vo = new PostCommentVO();
            BeanUtils.copyProperties(comment, vo);
            AuthorProfile author = profiles.get(comment.getAuthorId());
            if (author != null) {
                vo.setAuthorName(author.name());
                vo.setAuthorAvatar(author.avatar());
            }
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

    /**
     * 待审评论队列（管理员视角，先进先出）
     *
     * 返回 {@code PostCommentAuditVO} 而不是公开列表用的 {@code PostCommentVO}：
     * 后者刻意不含审核字段，靠「目标 VO 没有这些字段」脱敏；给公开 VO 加上 status
     * 等于把内部审核信息暴露给游客。按创建时间升序：审核是排队作业，先提交先处理。
     *
     * 坑：本方法不校验登录态与管理员角色，那两道在 Controller 的 {@code @SaCheckRole}。
     * 转 VO 时必须把 total 搬到新分页上，只取 records 会让前端分页组件永远只有一页。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值（10）、超过上限（50）收敛到上限
     * @return 分页结果（记录含审核状态与 AI 结论）；队列为空时返回空记录分页
     */
    @Override
    public Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize) {
        // ① 分页参数兜底纠正：页码错了不必让查询失败；pageSize 必须设上限，
        //    否则一个 pageSize=100000 就能把整表读进内存
        long safeCurrent = current < 1 ? 1 : current;
        long safeSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        safeSize = Math.min(safeSize, MAX_PAGE_SIZE);

        // ② 只查待审、按创建时间升序（先进先出）
        LambdaQueryWrapper<StudioPostComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .orderByAsc(StudioPostComment::getCreatedAt);

        Page<StudioPostComment> entityPage = this.page(new Page<>(safeCurrent, safeSize), wrapper);
        // ③ 作者信息（姓名 + 头像）一次查完（批量），不逐条查
        Map<Long, AuthorProfile> profiles = authorProfiles(entityPage.getRecords().stream()
                .map(StudioPostComment::getAuthorId).toList());

        // ④ 先建一个只保留 total/current/size 的空分页，再整体替换记录：
        //    直接 new Page 会丢掉 total，前端分页组件就翻不了页
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

    /**
     * 批量审核评论（通过 / 驳回），并在通过时维护帖子评论数、通知被回复者
     *
     * 一条 UPDATE 处理整批，逐条更新会把「勾选 20 条一次通过」变成 20 次往返。
     * 更新条件必须带「状态 = 待审」：已处理的不该被覆盖，这同时是幂等保护。
     * 评论数放在「通过」而不是「提交」时累加，保证「评论数」与「看得见的评论」一致。
     *
     * 坑：评论数必须按「帖子 + 条数」做增量；{@code this.update} 只返回 boolean，
     * 要处理条数必须用 {@code baseMapper.update}。
     *
     * @param request 审核请求：ids 至少一个、action 只能是 approve 或 reject、驳回需理由
     * @return 实际处理的条数；批次里没有待审评论时返回 0（不是异常）
     * @throws BusinessException 参数为 null（A0401）、未登录（A0201）、
     * 动作非法或驳回缺理由（A0401）
     */
    @Override
    public int audit(AuditRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "审核参数不能为空");
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long auditorId = StpUtil.getLoginIdAsLong();

        // ① 动作取值只认 approve / reject：非法值若放过去，后面两个分支都不会命中，
        //    会得到一条「既没通过也没驳回」的记录
        String action = StrUtil.trim(request.getAction());
        ThrowUtils.throwIf(!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action),
                ErrorCode.PARAMS_ERROR, "审核动作只能是 " + ACTION_APPROVE + " 或 " + ACTION_REJECT);

        boolean approved = ACTION_APPROVE.equals(action);
        // 驳回必须给理由：它是给作者看的，没有理由等于让作者自己猜哪里不合适
        ThrowUtils.throwIf(!approved && StrUtil.isBlank(request.getRejectReason()),
                ErrorCode.PARAMS_ERROR, "驳回时必须填写理由");

        // ② 先取出这一批里真正处于「待审」的评论：
        //    ① 保证幂等（已处理的不重复动）；② 通过后要按帖子统计各自的增量。
        //    这一步必须在 UPDATE 之前：一条 UPDATE 拿不到 postId
        List<StudioPostComment> targets = this.list(new LambdaQueryWrapper<StudioPostComment>()
                .in(StudioPostComment::getId, request.getIds())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue()));
        if (targets.isEmpty()) {
            // 一条待审的都没有：直接返回 0，不必再发 UPDATE（也没有通知该发）
            return 0;
        }

        // ③ 一条 UPDATE 处理整批，更新范围用上面查到的 id，条件仍带「待审」——
        //    查询与更新之间可能被别的管理员抢先处理，这道条件就是兜底
        LambdaUpdateWrapper<StudioPostComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(StudioPostComment::getId, targets.stream().map(StudioPostComment::getId).toList())
                .eq(StudioPostComment::getStatus, AuditStatusEnum.PENDING.getValue())
                .set(StudioPostComment::getStatus, approved
                        ? AuditStatusEnum.APPROVED.getValue()
                        : AuditStatusEnum.REJECTED.getValue())
                // 审核人写真人 ID：这是「人放的」与「机器放的」唯一的区分依据
                .set(StudioPostComment::getAuditBy, auditorId)
                .set(StudioPostComment::getAuditAt, LocalDateTime.now());
        if (!approved) {
            wrapper.set(StudioPostComment::getRejectReason, StrUtil.trim(request.getRejectReason()));
        }
        // this.update 只返回 boolean，这里要的是影响行数，故走 baseMapper.update
        int rows = baseMapper.update(null, wrapper);

        if (approved) {
            // ④ 评论数在「真正公开」这一刻才累加：发布时刻意不计数（那时还是待审），
            //    这样「评论数」与「看得见的评论」始终一致，不会出现"显示 1 条却点开没有"。
            //    先按帖子分组统计条数，同一帖子只发一次 UPDATE
            Map<Long, Long> incrementByPost = targets.stream()
                    .collect(Collectors.groupingBy(StudioPostComment::getPostId, Collectors.counting()));
            for (Map.Entry<Long, Long> entry : incrementByPost.entrySet()) {
                // 拼接的是内部统计出来的数字，不是用户输入；用 SQL 表达式避免读改写竞态
                studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                        .eq(StudioPost::getId, entry.getKey())
                        .setSql("comment_count = comment_count + " + entry.getValue()));
            }
            // ⑤ 回复通知：与 AI 判安全同一时机（评论真正可见），共用同一私有方法，
            //    保证「人工通过」与「AI 自动通过」两条路径的行为不会漂移
            for (StudioPostComment target : targets) {
                notifyReplyIfParent(target);
            }
        }
        return rows;
    }

    /**
     * 删除一条评论，连带删掉它下面的回复（逻辑删除）
     *
     * 只删顶层会留下 parentId 指向已删评论的孤儿回复，读者只看到一句没头没尾的话，
     * 所以顶层与回复必须在同一次删除里处理完。待审评论不能删、只能驳回：
     * 作者只知道「我提交了」，直接删掉他什么都收不到。
     *
     * 坑：评论数回退不能直接减，脏数据下会减成负数（列表页显示「-1 条评论」），
     * 必须用 GREATEST(..., 0) 兜底。
     *
     * @param id 评论 ID，必须大于 0，否则抛 A0401
     * @return 实际删除的条数（含它下面的回复）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 评论 id 不合法或状态不是已通过（A0401）、评论不存在（A0402）
     */
    @Override
    public int deleteComment(long id) {
        // ① 删评论是管理动作，必须先有登录态：不知道操作人就无法留痕
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long operatorId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "评论 id 不合法");

        // ② 取实体：后面要拿 postId 回退评论数，也要拿 status 做校验
        StudioPostComment target = this.getById(id);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "评论不存在");
        // ③ 只删「已通过」的：待审内容应当走驳回，让作者收到反馈；
        //    直接删掉等于什么都不告诉作者
        ThrowUtils.throwIf(target.getStatus() == null
                        || target.getStatus() != AuditStatusEnum.APPROVED.getValue(),
                ErrorCode.PARAMS_ERROR, "只能删除已通过的评论；待审内容请使用驳回");

        long postId = target.getPostId();

        // ④ 审计：管理员删的是别人的内容，光有 deleted_at 只知道时间、不知道人，
        //    出争议时必须能查到操作人。本表没有单独的「删除人自动填充」，
        //    元数据填充器只会把 updated_by 填成 0L（它拿不到登录态），所以这里自己写
        this.update(new LambdaUpdateWrapper<StudioPostComment>()
                .eq(StudioPostComment::getId, id)
                .set(StudioPostComment::getDeletedBy, operatorId));

        // ⑤ 级联：顶层评论连同它的回复一并逻辑删除。
        //    只删顶层会留下 parentId 指向已删评论的孤儿回复——前端会渲染出没有上下文的内容。
        //    this.remove 只返回 boolean，这里要的是实际删除条数（含回复），故走 baseMapper
        int rows = baseMapper.delete(new LambdaQueryWrapper<StudioPostComment>()
                .eq(StudioPostComment::getPostId, postId)
                .and(nested -> nested
                        .eq(StudioPostComment::getId, id)
                        .or()
                        .eq(StudioPostComment::getParentId, id)));

        // 评论数回退；GREATEST 兜底防止脏数据下减成负数（列表页会出现「-1 条评论」）
        studioPostMapper.update(null, new LambdaUpdateWrapper<StudioPost>()
                .eq(StudioPost::getId, postId)
                .setSql("comment_count = GREATEST(comment_count - " + rows + ", 0)"));
        return rows;
    }

    // ==================== 私有工具 ====================

    /**
     * 取下一个楼层号：当前帖子最大楼层 + 1（只给顶层评论用）
     *
     * 楼层号由服务端分配，前端决定会立刻出现重复与跳号。用一条 SELECT MAX(floor) 现算，
     * 不在帖子表上另存「已用楼层数」——多一份需要同步的状态就多一次不一致的机会。
     *
     * 坑：并发下可能算出相同楼层，这是刻意接受的：楼层只是展示序号，重复不影响功能。
     * 聚合结果在 MySQL 里可能是 BigDecimal 或 Long，必须按 Number 处理再取 int。
     *
     * @param postId 帖子 ID，调用方已保证该帖存在且已通过
     * @return 下一个可用楼层号，最小为 1
     */
    private int nextFloor(Long postId) {
        // ① 拼一条聚合查询，只取 max(floor) 一列，不把评论行读回来
        QueryWrapper<StudioPostComment> wrapper = new QueryWrapper<>();
        // 只取聚合列：不把整行评论读回内存，这个查询只为了拿一个数字
        wrapper.select("MAX(floor) AS max_floor").eq("post_id", postId);
        // 本版本的 getObj 需要一并传入转换函数；聚合结果可能是 BigDecimal，统一按 Number 处理
        Object result = this.getObj(wrapper, value -> value);
        // ② 该帖还没有顶层评论时聚合结果是 null，按 0 处理，加一得到第 1 楼
        int maxFloor = result instanceof Number number ? number.intValue() : 0;
        return maxFloor + 1;
    }

    /**
     * 批量查评论人姓名（一次查询，避免 N+1）
     *
     * 一次 IN 查询后，查询次数与评论条数无关。姓名与头像来自同一批行，必须一次取回，
     * 分开查等于把 1 次查询变成 2 次。只认在队成员，也刻意不加账号昵称兜底——
     * 保证公开列表这一处口径只有一种。
     *
     * 坑：拿空集合拼 IN () 是语法错误，MySQL 会直接报错；未绑定账号的档案 user_id 为 null，
     * 不跳过就会把 null 键放进映射。
     *
     * @param userIds 用户 ID 集合，可为 null 或空
     * @return userId → 作者档案（姓名必有值，头像可能为 null）；入参为空时返回空映射
     */
    private Map<Long, AuthorProfile> authorProfiles(List<Long> userIds) {
        // ① 空集合直接返回空映射——拿空集合拼 IN () 是语法错误，MySQL 会直接报错
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // ② 按 user_id IN 且 member_status = 在队，一次查出成员档案
        LambdaQueryWrapper<StudioMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioMember::getUserId, userIds)
                // 只认在队成员：离队成员的姓名不再出现在公开评论列表上
                .eq(StudioMember::getMemberStatus, MemberStatusEnum.IN_TEAM.getValue());
        List<StudioMember> members = studioMemberMapper.selectList(wrapper);

        // ③ 在内存里拼成 userId → 档案 的映射，跳过姓名为空与未绑定账号的档案
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
     * 评论人的展示信息（姓名 + 头像）
     *
     * 用不可变载体而不是两个并列 Map：两者必须属于同一条档案记录，
     * 拆开早晚会有人拼出「A 的名字 + B 的头像」。
     */
    private record AuthorProfile(String name, String avatar) {
    }
}

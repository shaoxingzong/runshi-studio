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
 * 两条贯穿全类的规则，读每个方法时都会用到：
 * - 楼中楼只支持两层：回复的回复仍挂在同一个顶层评论下，不再继续嵌套。
 * 这样列表组装只要「一次查询 + 内存分组」，不必递归；
 * - 先审后发：评论提交后是「待审」，只有审核通过（人工或 AI 判安全）才公开，
 * 帖子的 comment_count 也只在「真正公开」那一刻累加，保证
 * 「评论数」与「点开能看到的评论」始终一致。
 *
 * 列表组装是这个类最需要理解的地方：
 * WHERE post_id = ? AND status = 1 一次把整个帖子的评论查回来，
 * 再在内存里按 parentId 挂成树。
 * 绝不能写成「先查顶层，再对每条顶层查它的回复」——那是标准 N+1
 * （查 1 次列表，再对每条记录各查 1 次），一个热门帖子有 50 条顶层评论就是 50 次查询。
 *
 * 依赖方向同样是单向的：审核判定走 ContentAuditManager，通知投递走 NotificationService，
 * 两者都不认识评论表——「结论落到哪张表、要不要顺带维护帖子的评论数」是本类的事。
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
     * 取值必须与前端约定的字符串完全一致（大小写敏感）：审核动作是接口契约的一部分，
     * 换个写法前端就会拿到 A0401，而错误提示只会说「只能是 approve 或 reject」。
     */
    private static final String ACTION_APPROVE = "approve";

    /** 审核动作：驳回（驳回必须带理由，理由要给作者看） */
    private static final String ACTION_REJECT = "reject";

    /**
     * 分页每页条数的默认值与上限
     *
     * 上限用来防「一次拉全表」：待审队列里是全站所有用户的待审评论，
     * 不设上限时一个 pageSize=100000 就能把整张表读进内存。
     */
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_PAGE_SIZE = 50L;

    /**
     * 帖子表 Mapper
     *
     * 本类要往帖子表上做两件事：确认评论的归属帖子「存在且已通过」、
     * 以及原子地增减帖子的 comment_count。两者都是单表操作，且都发生在
     * 「审核通过 / 删除」这类高频路径上，直接走 Mapper 最省事。
     * 不注入 PostService 也避免了两边互相依赖——PostServiceImpl 已经依赖评论表了。
     */
    private final StudioPostMapper studioPostMapper;

    /**
     * 成员档案 Mapper
     *
     * 列表要显示评论人姓名，需要「按一批 user_id 反查成员姓名」的批量能力。
     * 只用它做一次 IN 查询，避免对每条评论各查一次（那是 N+1）。
     */
    private final StudioMemberMapper studioMemberMapper;

    /**
     * 内容审核（AI 三分法）
     *
     * 与帖子共用同一个 Manager：判定逻辑不区分内容类型，
     * 差别只在「结论落到哪张表、要不要顺带维护帖子的评论数」，那是本类的事。
     * 只让它负责判定、落库由本类完成，是为了保持依赖方向单向——
     * 反过来让 Manager 写评论表就会形成循环依赖（本项目全部使用构造器注入，成环会启动即失败）。
     */
    private final ContentAuditManager contentAuditManager;

    /**
     * 站内通知（评论通过后告知被回复者）
     *
     * 依赖方向单向：通知服务不认识评论，「标题正文怎么写」由本类决定。
     * 投递策略（幂等、不给自己发、失败不拖垮主业务）集中在 NotificationService，
     * 本类不必为它加任何防护。
     */
    private final NotificationService notificationService;

    /**
     * 发表评论，或回复某条顶层评论（先审后发）
     *
     * <p>为什么这样设计：评论沿用与发帖相同的「先审后发」，但准入门槛放宽到「登录即可」。
     * 门槛不同是有意的——发帖代表工作室对外发声，评论只是读者之间的交流；
     * 若评论也要求成员身份，等于把访客的参与渠道关掉。
     * 落库时状态一律写「待审」，公开与否交给后续审核决定。
     * 楼层号由服务端分配（当前帖子最大楼层 + 1），不接受前端传入：
     * 楼层是展示用的序号，让客户端决定会立刻出现重复与跳号。
     * 楼中楼只支持两层——回复的回复仍挂在同一个顶层评论下，不再继续嵌套，
     * 这样列表组装只要「一次查询 + 内存分组」，不必递归。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应，开头两句为不带编号的准入校验）：
     * <ol>
     *     <li>① 校验所属帖子存在且已通过——对待审 / 已驳回的帖子评论没有意义，
     *     而且那是一条绕过审核、往未公开内容上挂评论的路径；</li>
     *     <li>② 校验父评论合法——必须存在、必须属于同一帖子、必须本身是顶层。
     *     这一步必须排在组装实体之前：父评论不合法时，楼层号已经被算掉了；</li>
     *     <li>③ 组装实体：状态固定写待审，顶层评论才分配楼层号；</li>
     *     <li>④ 落库并校验返回值——save 只返回 boolean，失败要给 B0001 而不是静默返回 0；</li>
     *     <li>⑤ 异步送 AI 初判，不阻塞提交。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法刻意不累加帖子的 comment_count。
     * 评论此刻还是待审，计数会让用户看到「有 1 条评论」却点开什么都没有；
     * 计数只在「真正公开」那一刻累加——人工通过走审核接口，AI 判安全走回调。
     * 另外回调里只能使用在请求线程里提前取好的变量（commentId、正文），
     * 不能回头去取登录态：回调跑在虚拟线程上，拿不到请求上下文。
     *
     * @param request 评论请求：postId 与 content 必填（content 最长 1000 字符，与列宽一致）；
     * parentId 选填，不传表示直接评论帖子（有楼层号），传了表示回复某条顶层评论
     * （楼层号为 null）；不能为 null，为 null 抛 A0401
     * @return 新评论的 ID（雪花算法生成的 19 位长整型，不是数据库自增主键）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为空（A0401）、
     * 未登录（A0201）、帖子不存在或未过审（A0402）、父评论不合法（A0401）、
     * 入库失败（B0001）时抛出
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
     * <p>为什么这样设计：与帖子那一版是同一套规则——AI 的结论只对「还没人处置过」的
     * 内容有效。更新条件里带两个 eq，缺一不可：「未判」保证幂等
     * （幂等：重复调用结果一致，重复送审不会覆盖已有结论），
     * 「待审」保证管理员已经手动处置过的内容不会被后到的机器结论改写：
     * 人的决定优先于机器。audit_by 一律留空，因为 AI 不是人，不该伪造一个管理员 ID，
     * 「这是机器处置的」这件事由 ai_audit_status 记录。
     * 与帖子版唯一的差别是：通过之后要顺带维护帖子的 comment_count 并通知被回复者——
     * 这两件事都只能在「评论真正公开」的这一刻做。
     *
     * <p>执行步骤（与代码里的 ①②③④ 标注一一对应）：
     * <ol>
     *     <li>① 先取评论实体——通过后要拿 postId 去维护帖子评论数，
     *     而 update 语句拿不到这个值；取不到直接返回（评论可能已被删除）；</li>
     *     <li>② 按三分法决定是否改 status：安全则通过、违规则驳回并写驳回理由，
     *     灰色与判定失败只记录 ai_reason、status 保持待审；</li>
     *     <li>③ 执行更新。影响 0 行说明已被人工处置或已判过，这是正常情况，
     *     记一条 debug 后直接返回，不能当成错误；</li>
     *     <li>④ 判安全时再补两步：原子自增所属帖子的评论数、通知被回复者。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里抛异常。它跑在异步线程上，抛出去只会被线程池吞掉
     * 或记一条错误日志，评论照样停在待审——真正该做的是把「没写成」如实记下来。
     * 另外自增用的是 SQL 表达式 setSql 而不是「读出来 +1 再写回」：
     * 后者在并发下两个请求会互相覆盖，评论数偏小。
     *
     * @param commentId 评论 ID，由调用方在提交时就固定好（回调里取不到请求上下文）
     * @param result AI 结论，一定非空——AuditResult 的紧凑构造器会把 null 兜成「转人工」
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
     * <p>为什么这样设计：通知挂在「通过」而不是「提交」这一刻。评论先审后发，
     * 提交那一刻内容对世界还不可见——提前通知，被回复者点开帖子却找不到那条回复，
     * 是一次断裂的体验。人工通过与 AI 判安全共用本方法，保证两条路径行为一致
     * （复制一份的代价是：将来改通知文案或触发条件，总有一个人会漏改一处）。
     * 业务幂等键（重复投递能被识别出来的那组字段）用 postId 而非 commentId：
     * 同一帖子下的多次回复按话题聚合成一条通知——既避免「一屏十条回复通知」的轰炸，
     * 也让通知表的唯一键语义与产品语义天然一致。
     * 批量审核时逐条调用会有少量额外查询（父评论 + 帖子标题各一次）：
     * 批量审核频率低、量级小，而「单一实现防止两条路径逻辑漂移」比省几次查询重要。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 顶层评论没有「被回复者」，直接返回——不能给帖子作者发，
     *     那是另一类通知，混进来会让「有人回复了你」变得名不副实；</li>
     *     <li>② 取父评论：父评论可能已被删除（悬空场景），跳过通知，
     *     但不影响评论本身——通知是附属动作，不该让主流程失败；</li>
     *     <li>③ 取帖子标题拼文案并投递。标题取不到时用「原帖子」兜底，
     *     否则通知里会出现「《null》」这种明显是 bug 的文案。</li>
     * </ol>
     *
     * <p>容易踩的坑：父评论的作者可能正是当前评论的作者（自己回复自己），
     * 这里不做判断——「不给自己发」的规则集中在 NotificationService 里，
     * 分散到每个调用点判断，迟早会有漏掉的一处。
     *
     * @param comment 已通过审核的评论，调用方保证非空；parentId 为 null 时本方法直接返回
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
     * <p>为什么这样设计：一次查询把该帖全部已通过的评论取回来，再在内存里
     * 按 parentId 分组挂到各自的顶层评论下。绝不能写成「先查顶层，
     * 再对每条顶层查它的回复」——那是标准 N+1（查 1 次列表，再对每条记录各查 1 次），
     * 一个热门帖子有 50 条顶层评论就是 50 次额外查询。
     * 只支持两层也让这套组装逻辑不必递归：回复的回复仍挂在同一个顶层下，
     * 所以「一次遍历 + 一次挂载」就够，深度不会再增加。
     * 排序按 id 升序：雪花 ID（全局趋势递增的长整型主键）天然反映创建先后，
     * 比按 created_at 排序更稳——同一毫秒内创建的评论也有确定的先后。
     *
     * <p>执行步骤（与代码里的 ①②③ 标注一一对应）：
     * <ol>
     *     <li>① 校验 id 并一次查完该帖全部已通过评论，按 id 升序；</li>
     *     <li>② 批量查评论人姓名——一次 IN 查询，不逐条查；</li>
     *     <li>③ 在内存里挂树：parentId 为空的进顶层列表，其余按 parentId 暂存，
     *     最后一次性挂回各自的顶层评论。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把「按 parentId 分组」写成对每条顶层评论做一次列表过滤
     * （那等于在内存里重演一遍 N+1）。一次遍历、边遍历边分组才是 O(n)。
     * 另外这里只查「已通过」，待审与已驳回的评论对公众等同于不存在——
     * 这个条件必须写进 SQL 的 WHERE，而不是查出来再在内存里过滤，
     * 后者会让「有没有评论」的判断依赖内存逻辑，容易漏。
     *
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 顶层评论列表（每条自带 children，没有回复时为空列表）；该帖没有已通过评论时
     * 返回空列表而不是 null，调用方不必判空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子 id 不合法时抛出（A0401）
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

        // ② 作者名一次查完（批量，避免逐条查的 N+1）
        Map<Long, String> names = authorNames(all.stream()
                .map(StudioPostComment::getAuthorId).toList());

        // ③ 内存挂树：顶层单独收集，回复按 parentId 暂存。
        //    一次遍历边分组，不要对每条顶层再过滤一遍全量列表
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

    /**
     * 待审评论队列（管理员视角，先进先出）
     *
     * <p>为什么这样设计：返回 PostCommentAuditVO 而不是 PostCommentVO——后者服务于
     * 游客可见的公开评论列表，刻意不含审核字段（本项目「靠目标 VO 没有这些字段来脱敏」
     * 的一贯做法）；队列则必须展示审核状态与 AI 的初判结论，管理员才知道机器在犹豫什么。
     * 两者用途互斥，不能混用：给公开 VO 加上 status，等于把内部审核信息暴露给游客。
     * 排序按创建时间升序，与帖子待审队列一致：审核是排队作业，先提交的应当先被处理，
     * 否则老内容会被不断涌入的新内容压到队尾。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应）：
     * <ol>
     *     <li>① 纠正分页参数（页码小于 1 按 1、pageSize 收敛到上限）——
     *     传错页码没必要让整个队列打不开；</li>
     *     <li>② 按「状态 = 待审」分页查询，创建时间升序；</li>
     *     <li>③ 批量查评论人姓名，不逐条查；</li>
     *     <li>④ 转成审核 VO 分页，同时保留 total / current / size。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验登录态与管理员角色——这两道在 Controller 上
     * 由 @SaCheckRole 拦下。队列里是全站所有用户的待审评论，
     * 换给普通用户调用就是一次内容泄露。
     * 另外转 VO 时必须新建一个分页并把 total 搬过去：只取 records 会让前端分页组件
     * 拿不到总数，队列看起来永远只有一页。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值（10）、超过上限（50）收敛到上限
     * @return 分页结果（记录含审核状态与 AI 结论）；队列为空时返回空记录分页，不会返回 null
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
        // ③ 作者名一次查完（批量），不逐条查
        Map<Long, String> names = authorNames(entityPage.getRecords().stream()
                .map(StudioPostComment::getAuthorId).toList());

        // ④ 先建一个只保留 total/current/size 的空分页，再整体替换记录：
        //    直接 new Page 会丢掉 total，前端分页组件就翻不了页
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

    /**
     * 批量审核评论（通过 / 驳回），并在通过时维护帖子评论数、通知被回复者
     *
     * <p>为什么这样设计：与帖子审核同一套路——一条 UPDATE 处理整批，而不是循环逐条更新。
     * 批量审核的意义就在于此，逐条更新会把「勾选 20 条一次通过」变成 20 次数据库往返。
     * 更新条件必须带「状态 = 待审」：已通过 / 已驳回的记录不该被重复处理，
     * 否则后一次审核会覆盖掉前一次的结论，这也是一种幂等保护
     * （幂等：重复调用与调用一次的结果一致）。
     * 与帖子审核的差别在于通过之后要额外做两件事：按帖子累加 comment_count、
     * 通知被回复者。计数放在「通过」这一刻而不是「提交」那一刻，
     * 是为了让「评论数」与「看得见的评论」始终一致——否则会出现
     * 「列表显示 1 条评论，点开却什么都没有」。
     * 驳回必须填理由：理由要给作者看，没有理由等于让作者自己猜哪里不合适。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应）：
     * <ol>
     *     <li>① 校验请求体、登录态、动作取值与驳回理由；</li>
     *     <li>② 先查出这一批里真正处于「待审」的评论——既保证幂等，
     *     也拿到按帖子统计增量所需的 postId；一条都没有就直接返回 0，
     *     这个查询必须排在更新之前：一条 UPDATE 拿不到 postId；</li>
     *     <li>③ 一条 UPDATE 更新整批并拿到受影响行数；</li>
     *     <li>④ 通过时按帖子分组累加评论数——同帖子多条合成一次 UPDATE；</li>
     *     <li>⑤ 逐条通知被回复者，与 AI 判安全共用同一私有方法。</li>
     * </ol>
     *
     * <p>容易踩的坑：评论数的累加必须按「帖子 + 条数」做增量，
     * 不能对每条评论各更新一次——那样同一帖子下 N 条评论就是 N 次 UPDATE，
     * 而且并发下更容易相互干扰。另外 this.update 只返回 boolean，
     * 要「已处理 N 条」这个数字必须用 baseMapper.update 的返回值（受影响行数）。
     *
     * @param request 审核请求：ids 至少一个、action 只能是 approve 或 reject、
     * 驳回时 rejectReason 必填（最长 256 字符）；不能为 null，为 null 抛 A0401
     * @return 实际处理的条数；批次里没有待审评论时返回 0（不是异常），
     * 因此返回值可能小于传入的 id 个数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 参数为 null（A0401）、
     * 未登录（A0201）、动作非法或驳回缺理由（A0401）时抛出
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
     * <p>为什么这样设计：只删顶层评论会留下 parentId 指向已删评论的孤儿回复，
     * 前端会渲染出没有上下文的内容，读者只看到一句没头没尾的话。
     * 所以顶层与它的回复必须在同一次删除里处理完。
     * 待审评论不能走删除，只能驳回：作者只知道「我提交了」，直接删掉他什么都收不到；
     * 驳回能把理由送到他眼前。删除只服务于「已通过的内容要下线」与
     * 「已驳回的内容要清理」这两种场景。
     * 删除前先把操作人写进 deleted_by：光有删除时间只能知道「什么时候删的」，
     * 出争议时必须能查到「谁删的」。
     *
     * <p>执行步骤（与代码里的 ①②③④⑤ 标注一一对应）：
     * <ol>
     *     <li>① 校验登录态与 id——删评论是管理动作，不知道操作人就无法留痕；</li>
     *     <li>② 取出评论，不存在报 A0402；</li>
     *     <li>③ 校验状态必须是已通过——待审与已驳回的都不该被删，
     *     这一步必须排在真正删除之前，否则删完才发现状态不对；</li>
     *     <li>④ 写入删除人（审计）；</li>
     *     <li>⑤ 按「本条 + 以本条为父的回复」删除并拿实际条数，
     *     再把帖子的评论数减回同样的条数。</li>
     * </ol>
     *
     * <p>容易踩的坑：评论数回退不能直接减，脏数据下会减成负数，
     * 列表页就会显示「-1 条评论」；必须用 GREATEST(..., 0) 兜底。
     * 另外删除条件里的 post_id 不能省：少了它，一个 id 恰好等于本条的回复
     * （不同帖子的评论主键理论上不会相等，但少了条件会让语义变得依赖主键全局唯一这个隐含前提）
     * 就会被误删，条件写全才不依赖任何隐含前提。
     *
     * @param id 评论 ID，必须大于 0，否则抛 A0401
     * @return 实际删除的条数（含它下面的回复），前端可据此提示「已删除 N 条」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 评论 id 不合法或状态不是已通过（A0401）、评论不存在（A0402）时抛出
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
     * <p>为什么这样设计：楼层号由服务端分配，不接受前端传入——让客户端决定会立刻
     * 出现重复与跳号。这里用一条 SELECT MAX(floor) 现算，而不是在帖子表上另存一个
     * 「已用楼层数」：多存一份需要同步的状态，就多一次两边不一致的机会。
     * 这个查询走的是 idx_post_status_id (post_id, status, id) 的最左前缀，代价很小。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拼一条聚合查询，只取 max(floor) 一列，不把评论行读回来；</li>
     *     <li>② 结果为空（该帖还没有任何顶层评论）时按 0 处理，加一得到第 1 楼。</li>
     * </ol>
     *
     * <p>容易踩的坑：并发下可能重复——两个顶层评论同时提交，都会读到同一个最大值
     * 而算出相同楼层。这是刻意接受的：楼层号只是展示用的序号，重复不影响任何功能，
     * 而要为它加唯一索引 + 冲突重试，对当前量级是过度设计。
     * 另外聚合结果在 MySQL 里可能是 BigDecimal 或 Long，必须按 Number 处理再取 int，
     * 直接强转会 ClassCastException。
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
     * <p>为什么这样设计：列表页要为每条评论填作者名，若逐条查询就是标准的 N+1
     * （查 1 次列表，再对每条记录各查 1 次）——一个帖子 50 条评论就是 50 次额外查询。
     * 改成一次 IN 查询后，查询次数与评论条数无关。
     * 只认「在队成员」的姓名：这是对外的展示口径，离队成员的姓名不该继续出现在
     * 公开评论列表上（这里也刻意不加账号昵称兜底，保证这一处口径只有一种）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空集合直接返回空映射——拿空集合拼 IN () 是语法错误，MySQL 会直接报错；</li>
     *     <li>② 按 user_id IN 且 member_status = 在队，一次查出成员档案；</li>
     *     <li>③ 在内存里拼成 userId → 姓名 的映射，跳过姓名为空与未绑定账号的档案。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的映射可能缺少某些 userId（作者已离队或没绑档案），
     * 调用方用 get 取值后必须允许为 null，不能假定「每条评论都查得到名字」。
     * 另外未绑定账号的档案 user_id 为 null，不跳过就会把 null 键放进映射。
     *
     * @param userIds 用户 ID 集合，可为 null 或空
     * @return userId → 成员姓名；入参为空时返回空映射（不是 null）
     */
    private Map<Long, String> authorNames(List<Long> userIds) {
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

        // ③ 在内存里拼成 userId → 姓名 的映射，跳过姓名为空与未绑定账号的档案
        Map<Long, String> names = new HashMap<>();
        for (StudioMember member : members) {
            // 未绑定账号的档案 user_id 为空，姓名也可能没填——两者都要跳过，否则映射里会出现 null 键
            if (member.getUserId() != null && StrUtil.isNotBlank(member.getName())) {
                names.put(member.getUserId(), member.getName());
            }
        }
        return names;
    }
}

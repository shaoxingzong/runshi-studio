package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.vo.post.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.post.PostCommentVO;

import java.util.List;

/**
 * 帖子评论服务接口（楼中楼）
 *
 * 楼中楼指「评论的评论」：一条顶层评论下挂若干回复，回复本身不再继续嵌套。
 * 只支持两层，因此读取时一次查完整个帖子的评论、在内存里按 parentId 挂成树即可，
 * 不必递归查询（见实现的 listApprovedByPost）。
 *
 * 准入规则：发表评论只需登录（不要求是工作室成员）——
 * 这是与发帖的区别：发帖限成员，评论向所有登录用户开放。
 * 浏览评论对游客开放。
 *
 * 先审后发：评论发布后同样是「待审」，通过审核后才会出现在公开列表，
 * 并且此时才累加帖子的 comment_count——发布时不加，避免「有评论数但看不到评论」。
 *
 * @author shaoshing
 */
public interface PostCommentService extends IService<StudioPostComment> {

    /**
     * 发表评论（登录用户）
     *
     * <p>为什么这样设计：评论沿用与发帖相同的「先审后发」，但准入放宽到「登录即可」。
     * 门槛不同是有意的：发帖代表工作室对外发声，评论只是读者之间的交流；
     * 若评论也要求成员身份，等于把访客的参与渠道关掉。
     * 落库时状态一律写「待审」，公开与否由后续审核决定。
     * 楼层号由服务端分配（当前帖子最大楼层 + 1），不接受前端传入——
     * 楼层是展示用的序号，让客户端决定会立刻出现重复与跳号。
     *
     * <p>执行步骤（实现里的 ①② 标注与下面一一对应）：
     * <ol>
     *     <li>校验所属帖子存在且已通过——对待审 / 已驳回的帖子评论没有意义，
     *     而且那是一条绕过审核往未公开内容上挂评论的路径；</li>
     *     <li>校验父评论合法：必须存在、必须属于同一帖子、必须本身是顶层
     *     （父评论的父评论不为空说明它已经是回复，再往下挂就变成三层了）；</li>
     *     <li>组装实体并落库为「待审」，顶层评论分配楼层号；</li>
     *     <li>异步送 AI 初判，不阻塞提交。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法刻意不累加帖子的 comment_count。
     * 评论此刻还是待审，计数会让用户看到「有 1 条评论」却点开什么都没有；
     * 计数只在「真正公开」那一刻累加——人工通过走审核接口，AI 判安全走回调。
     *
     * @param request 评论请求（postId、content 必填；parentId 选填，
     * 不传表示直接评论帖子），不能为 null
     * @return 新评论的 ID（雪花算法生成的 19 位长整型）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在或未过审（A0402）、父评论不合法（A0401）、入库失败（B0001）时抛出
     */
    long addComment(PostCommentAddRequest request);

    /**
     * 某帖子的公开评论（游客可调用）：仅已通过，按楼层组装成楼中楼
     *
     * <p>为什么这样设计：一次查询把该帖全部已通过的评论取回来，再在内存里
     * 按 parentId 分组挂到各自的顶层评论下。绝不能写成「先查顶层，
     * 再对每条顶层查它的回复」——那是标准 N+1（查 1 次列表，
     * 再对每条记录各查 1 次），一个热门帖子有 50 条顶层评论就是 50 次查询。
     * 只支持两层也让这套组装逻辑不必递归。
     *
     * <p>执行步骤（实现里的 ①②③ 标注与下面一一对应）：
     * <ol>
     *     <li>一次查完该帖全部已通过评论，按 id 升序（雪花 ID 递增，等价于按时间有序）；</li>
     *     <li>批量查评论人姓名——一次 IN 查询，不逐条查；</li>
     *     <li>在内存里挂树：parentId 为空的进顶层列表，其余按 parentId 暂存，
     *     最后一次性挂回各自的顶层评论。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把「按 parentId 分组」写成对每条顶层评论做一次
     * 列表过滤（那等于在内存里重演 N+1）。一次遍历、边遍历边分组才是 O(n)。
     *
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 顶层评论列表（每条自带 children，回复的 children 为空列表）；
     * 无已通过评论时返回空列表，不返回 null，调用方不必判空
     */
    List<PostCommentVO> listApprovedByPost(long postId);

    /**
     * 待审队列（管理员）：仅「待审」，先进先出
     *
     * <p>为什么这样设计：返回 PostCommentAuditVO 而不是 PostCommentVO——
     * 后者服务于游客可见的公开评论列表，刻意不含审核字段（这是本项目
     * 「靠目标 VO 没有这些字段来脱敏」的一贯做法）；队列则必须展示审核状态
     * 与 AI 的初判结论，管理员才知道机器在犹豫什么。两者用途互斥，不能混用。
     * 排序按创建时间升序，与帖子待审队列一致：先提交的应当先被处理，
     * 否则老内容会被不断涌入的新内容压到队尾。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>纠正分页参数（页码小于 1 按 1、pageSize 收敛到上限），不抛异常；</li>
     *     <li>按「状态 = 待审」分页查询，创建时间升序；</li>
     *     <li>批量查评论人姓名，再转成审核 VO 分页。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验登录态与管理员角色——这两道在 Controller 上
     * 由 @SaCheckRole 拦下。队列里是全站所有用户的待审评论，
     * 换给普通用户调用就是一次内容泄露。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * <p>为什么这样设计：与帖子审核同一套路——一条 UPDATE 处理整批，
     * 更新条件带「状态 = 待审」保证幂等（重复提交同一批不会二次生效）。
     * 与帖子审核的差别在于通过之后要额外做两件事：
     * 累加所属帖子的 comment_count，并通知被回复者。
     * 计数放在「通过」这一刻而不是「提交」那一刻，是为了让
     * 「评论数」与「看得见的评论」始终一致。
     *
     * <p>执行步骤（实现里的 ①② 标注与下面一一对应）：
     * <ol>
     *     <li>先查出这一批里真正处于「待审」的评论：既保证幂等，
     *     也拿到按帖子统计增量所需的 postId；一条都没有就直接返回 0；</li>
     *     <li>一条 UPDATE 更新整批，拿到实际影响行数；</li>
     *     <li>通过时按帖子分组累加评论数，再逐条通知被回复者；</li>
     *     <li>返回影响行数。</li>
     * </ol>
     *
     * <p>容易踩的坑：评论数的累加必须按「帖子 + 条数」做增量，
     * 不能对每条评论各更新一次——那样同一帖子下 N 条评论就是 N 次 UPDATE，
     * 而且并发下更容易相互干扰。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、
     * 驳回时 rejectReason 必填），不能为 null
     * @return 实际处理的条数（批次里没有待审评论时返回 0）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 动作非法或驳回缺理由（A0401）时抛出
     */
    int audit(AuditRequest request);

    /**
     * 删除评论（管理员）：连带删除它的回复，并记录删除人
     *
     * <p>为什么这样设计：只删顶层评论会留下 parentId 指向已删评论的孤儿回复，
     * 前端会渲染出没有上下文的内容，所以顶层与它的回复必须在同一次删除里处理完。
     * 删除前先把操作人写进 deleted_by：评论表有这一列，
     * 光有删除时间只知道「什么时候删的」，出争议时必须能查到「谁删的」。
     * 删除后还要把所属帖子的评论数减回去，否则帖子列表会一直显示一个偏大的数字。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态与 id；</li>
     *     <li>取出评论，不存在报 A0402；</li>
     *     <li>校验状态必须是已通过——待审的走驳回，不走删除；</li>
     *     <li>写入删除人；</li>
     *     <li>按「本条 + 以本条为父的回复」删除，拿到实际条数；</li>
     *     <li>把帖子的评论数减回删除条数，并兜底不减成负数。</li>
     * </ol>
     *
     * <p>容易踩的坑：评论数回退不能直接减，脏数据下会减成负数，
     * 列表页就会显示「-1 条评论」；必须用 GREATEST(..., 0) 兜底。
     *
     * @param id 评论 ID，必须大于 0，否则抛 A0401
     * @return 实际删除的条数（含回复），前端可据此提示「已删除 N 条」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 评论不存在（A0402）、状态不是已通过（A0401）时抛出
     */
    int deleteComment(long id);
}

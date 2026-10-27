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
 * 只支持两层，因此读取时一次查完整个帖子的评论、在内存里按 parentId 挂成树即可。
 *
 * 准入规则：发表评论只需登录（不要求是工作室成员）——发帖代表工作室对外发声，
 * 评论只是读者之间的交流。浏览评论对游客开放。
 *
 * 先审后发：评论发布后同样是「待审」，通过审核后才出现在公开列表，
 * 并且此时才累加帖子的 comment_count——发布时不加，避免「有评论数但看不到评论」。
 *
 * @author shaoshing
 */
public interface PostCommentService extends IService<StudioPostComment> {

    /**
     * 发表评论（登录用户）
     *
     * 沿用与发帖相同的「先审后发」，但准入放宽到「登录即可」：若评论也要求成员身份，
     * 等于把访客的参与渠道关掉。楼层号由服务端分配（当前帖子最大楼层 + 1），
     * 不接受前端传入——让客户端决定会立刻出现重复与跳号。
     *
     * 坑：本方法刻意不累加帖子的 comment_count。评论此刻还是待审，
     * 计数会让用户看到「有 1 条评论」却点开什么都没有；计数只在真正公开那一刻累加。
     *
     * @param request 评论请求（postId、content 必填；parentId 选填，不传表示直接评论帖子），
     *                不能为 null
     * @return 新评论的 ID（雪花算法生成的 19 位长整型）
     * @throws BusinessException 未登录（A0201）、帖子不存在或未过审（A0402）、
     *         父评论不合法（A0401）、入库失败（B0001）
     */
    long addComment(PostCommentAddRequest request);

    /**
     * 某帖子的公开评论（游客可调用）：仅已通过，按楼层组装成楼中楼
     *
     * 一次查询把该帖全部已通过的评论取回来，再在内存里按 parentId 分组挂到各自的顶层评论下。
     * 绝不能「先查顶层，再对每条顶层查它的回复」——热门帖子有 50 条顶层评论就是 50 次查询。
     *
     * 坑：不要把「按 parentId 分组」写成对每条顶层评论做一次列表过滤，
     * 那等于在内存里重演 N+1。一次遍历、边遍历边分组才是 O(n)。
     *
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 顶层评论列表（每条自带 children，回复的 children 为空列表）；
     *         无已通过评论时返回空列表，不返回 null
     */
    List<PostCommentVO> listApprovedByPost(long postId);

    /**
     * 待审队列（管理员）：仅「待审」，先进先出
     *
     * 返回 PostCommentAuditVO 而不是 PostCommentVO：后者服务于公开列表、刻意不含审核字段，
     * 队列则必须展示审核状态与 AI 初判结论。两者用途互斥，不能混用。
     * 按创建时间升序，否则老内容会被不断涌入的新内容压到队尾。
     *
     * 坑：本方法不校验登录态与管理员角色，这两道在 Controller 上由 @SaCheckRole 拦下。
     * 队列里是全站所有用户的待审评论，换给普通用户调用就是一次内容泄露。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * 一条 UPDATE 处理整批，更新条件带「状态 = 待审」保证幂等。
     * 与帖子审核的差别在于通过之后要额外做两件事：累加所属帖子的 comment_count，
     * 并通知被回复者。计数放在「通过」这一刻而不是「提交」那一刻，
     * 是为了让「评论数」与「看得见的评论」始终一致。
     *
     * 坑：评论数必须按「帖子 + 条数」做增量，不能对每条评论各更新一次——
     * 同一帖子下 N 条评论就是 N 次 UPDATE，并发下还更容易相互干扰。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、
     *                驳回时 rejectReason 必填），不能为 null
     * @return 实际处理的条数（批次里没有待审评论时返回 0）
     * @throws BusinessException 未登录（A0201）、动作非法或驳回缺理由（A0401）
     */
    int audit(AuditRequest request);

    /**
     * 删除评论（管理员）：连带删除它的回复，并记录删除人
     *
     * 只删顶层评论会留下 parentId 指向已删评论的孤儿回复，前端会渲染出没有上下文的内容。
     * 删除前先把操作人写进 deleted_by：光有删除时间只知道「什么时候删的」，
     * 出争议时必须能查到「谁删的」。
     *
     * 坑：评论数回退不能直接减，脏数据下会减成负数，列表页就会显示「-1 条评论」；
     * 必须用 GREATEST(..., 0) 兜底。
     *
     * @param id 评论 ID，必须大于 0，否则抛 A0401
     * @return 实际删除的条数（含回复），前端可据此提示「已删除 N 条」
     * @throws BusinessException 未登录（A0201）、评论不存在（A0402）、状态不是已通过（A0401）
     */
    int deleteComment(long id);
}

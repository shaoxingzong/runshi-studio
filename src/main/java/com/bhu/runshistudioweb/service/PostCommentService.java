package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.PostCommentAddRequest;
import com.bhu.runshistudioweb.model.entity.StudioPostComment;
import com.bhu.runshistudioweb.model.vo.PostCommentAuditVO;
import com.bhu.runshistudioweb.model.vo.PostCommentVO;

import java.util.List;

/**
 * 帖子评论服务接口（楼中楼）
 *
 * author: shaoshing
 *
 * <p><b>准入规则</b>：发表评论只需<b>登录</b>（不要求是工作室成员）——
 * 这是与发帖的区别：发帖限成员，评论向所有登录用户开放。
 * 浏览评论对<b>游客开放</b>。
 *
 * <p><b>先审后发</b>：评论发布后同样是「待审」，通过审核后才会出现在公开列表，
 * 并且<b>此时才累加帖子的 comment_count</b>——发布时不加，避免"有评论数但看不到评论"。
 *
 * <p><b>楼中楼只支持两层</b>：回复依附于顶层评论，不再继续嵌套。
 * 读取时一次查完整个帖子的评论后在内存里挂成树（见实现的 {@code listApprovedByPost}），
 * 返回的是顶层列表，每条自带 {@code children}。
 */
public interface PostCommentService extends IService<StudioPostComment> {

    /**
     * 发表评论（登录用户）
     *
     * <p>校验顺序：登录 → 帖子存在且已通过 → 父评论合法（若回复）。
     * 顶层评论自动分配楼层号（当前帖子最大楼层 + 1）。
     * 通过后落库为「待审」，随后由后台异步交给 AI 初判（见 {@code ContentAuditManager}）。
     *
     * @param request 评论请求（postId、content 必填；parentId 选填）
     * @return 新评论 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在或未过审、
     *         父评论不合法、入库失败时抛出
     */
    long addComment(PostCommentAddRequest request);

    /**
     * 某帖子的公开评论（游客可调用）：仅已通过，按楼层组装成楼中楼
     *
     * @param postId 帖子 ID
     * @return 顶层评论列表（每条带 {@code children}）；无评论返回空列表，不返回 null
     */
    List<PostCommentVO> listApprovedByPost(long postId);

    /**
     * 待审队列（管理员）：仅「待审」，先进先出
     *
     * <p>返回 {@link PostCommentAuditVO} 而不是 {@link PostCommentVO}：
     * 后者服务于<b>游客可见</b>的公开评论列表，刻意不含审核字段；
     * 队列则必须展示审核状态与 AI 的初判结论。两者用途互斥，不能混用。
     *
     * @param current  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * <p>与帖子审核的差别：<b>通过时要累加所属帖子的 comment_count</b>——
     * 评论发布时刻意不计数（那时还是待审），计数发生在真正公开这一刻，
     * 这样"评论数"与"看得见的评论"始终一致。
     *
     * @param request 审核请求
     * @return 实际处理的条数
     */
    int audit(AuditRequest request);

    /**
     * 删除评论（管理员）：连带删除它的回复，并记录删除人
     *
     * <p>只处理<b>已通过</b>的评论（待审的走驳回，不走删除）。
     * 级联是必须的：只删顶层会留下找不到父级的孤儿回复。
     *
     * @param id 评论 ID
     * @return 实际删除的条数（含回复）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 评论不存在或不是已通过状态时抛出
     */
    int deleteComment(long id);
}

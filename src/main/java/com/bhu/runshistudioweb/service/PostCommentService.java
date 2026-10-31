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
 * @author shaoshing
 */
public interface PostCommentService extends IService<StudioPostComment> {

    /**
     * 发表评论（登录用户）
     *
     * @param request 评论请求（postId、content 必填；parentId 选填，不传表示直接评论帖子），不能为 null
     * @return 新评论的 ID（雪花算法生成的 19 位长整型）
     * @throws BusinessException 未登录（A0201）、帖子不存在或未过审（A0402）、父评论不合法（A0401）、入库失败（B0001）
     */
    long addComment(PostCommentAddRequest request);

    /**
     * 某帖子的公开评论（游客可调用）：仅已通过，按楼层组装成楼中楼
     *
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 顶层评论列表（每条自带 children，回复的 children 为空列表）；无已通过评论时返回空列表，不返回 null
     */
    List<PostCommentVO> listApprovedByPost(long postId);

    /**
     * 待审队列（管理员）：仅「待审」，先进先出
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostCommentAuditVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、驳回时 rejectReason 必填），不能为 null
     * @return 实际处理的条数（批次里没有待审评论时返回 0）
     * @throws BusinessException 未登录（A0201）、动作非法或驳回缺理由（A0401）
     */
    int audit(AuditRequest request);

    /**
     * 删除评论（管理员）：连带删除它的回复，并记录删除人
     *
     * @param id 评论 ID，必须大于 0，否则抛 A0401
     * @throws BusinessException 未登录（A0201）、评论不存在（A0402）、状态不是已通过（A0401）
     */
    int deleteComment(long id);
}

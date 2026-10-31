package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.post.AuditRequest;
import com.bhu.runshistudioweb.model.dto.post.PostAddRequest;
import com.bhu.runshistudioweb.model.dto.post.PostAdminQueryRequest;
import com.bhu.runshistudioweb.model.dto.post.PostUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioPost;
import com.bhu.runshistudioweb.model.vo.post.PostFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostFrontVO;
import com.bhu.runshistudioweb.model.vo.post.PostSelfDetailVO;
import com.bhu.runshistudioweb.model.vo.post.PostVO;
import com.bhu.runshistudioweb.model.vo.post.PostAdminDetailVO;

/**
 * 帖子服务接口
 *
 * @author shaoshing
 */
public interface PostService extends IService<StudioPost> {

    /**
     * 成员发布帖子
     *
     * @param request 发布请求（标题、正文必填；摘要与封面选填，为空时由服务端兜底生成）。请求体里没有 status 字段，审核状态由服务端决定
     * @return 新帖子的 ID（雪花算法生成的 19 位长整型）
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、正文过长或含外链图片（A0401）、入库失败（B0001）
     */
    long addPost(PostAddRequest request);

    /**
     * 点赞帖子（必须登录，幂等）
     *
     * @param userId 登录用户 ID，由 Controller 从登录态取，不接受请求体传入
     * @param postId 帖子 ID，必须大于 0，否则抛 A0401
     * @return 点赞后的最新点赞数（已赞过时原样返回；计数列为空时按 0 返回）
     * @throws BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    Long likePost(long userId, long postId);

    /**
     * 公开列表：仅已通过，置顶优先 + 时间倒序（游客可调用）
     *
     * @param current 页码，小于 1 时按 1 处理（不报错）
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @param keyword 搜索关键词（可选）：同时匹配标题与摘要；为空表示不过滤
     * @return 分页结果；没有已通过的帖子时返回空记录的分页对象，不会返回 null
     */
    Page<PostFrontVO> listApprovedByPage(long current, long pageSize, String keyword);

    /**
     * 公开详情：仅已通过（游客可调用），并原子自增浏览量
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含 Markdown 正文）
     * @throws BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    PostFrontDetailVO getApprovedDetail(long id);

    /**
     * 我的发帖：作者看自己的，含待审与已驳回（驳回时带理由）
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果（含状态、驳回理由、AI 初判结论）；该作者没有帖子时返回空记录分页
     * @throws BusinessException 未登录时抛出（A0201）
     */
    Page<PostVO> listMyByPage(long current, long pageSize);

    /**
     * 待审队列（管理员）：仅「待审」，按提交时间先进先出
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、驳回时 rejectReason 必填且不超过 256 字）
     * @return 实际处理的条数（已在审核前被处理过的不计入，可能小于传入的 id 个数）
     * @throws BusinessException 未登录（A0201）、动作非法或驳回缺理由（A0401）
     */
    int audit(AuditRequest request);

    /**
     * 管理端全量列表：三种状态均可查，支持按状态 / 标题 / 作者筛选（最新提交在前）
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页），不能为 null
     * @return 分页结果（含作者展示名）；筛选不到任何内容时返回空记录分页
     * @throws BusinessException 查询参数为 null、审核状态取值非法（A0401）
     */
    Page<PostVO> listForAdmin(PostAdminQueryRequest request);

    /**
     * 删除帖子（逻辑删除），同事务级联删除该帖的全部评论与点赞关系
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、帖子尚在待审（A0401）
     */
    int deletePost(long id);

    /**
     * 置顶 / 取消置顶
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、取值非法或对非已通过帖子置顶（A0401）
     */
    void updatePinned(long id, int pinned);

    /**
     * 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
     *
     * @param request 编辑请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、正文过长或含外链图片（A0401）
     */
    void updatePost(PostUpdateRequest request);

    /**
     * 作者修改自己的帖子（待审 / 已驳回 / 已通过均可；保存后重新送审）
     *
     * @param request 改稿请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws BusinessException 未登录（A0201）、帖子不存在 / 非本人（均 A0402）、正文过长或含外链图片（A0401）
     */
    void updateMyPost(PostUpdateRequest request);

    /**
     * 管理端帖子详情（含 Markdown 正文）
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文），三种审核状态都会返回
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）
     */
    PostAdminDetailVO getPostForAdmin(long id);

    /**
     * 作者读自己帖子的详情（含正文），不累加浏览量
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文）
     * @throws BusinessException 未登录（A0201）、帖子不存在 / 非本人（均 A0402）
     */
    PostSelfDetailVO getMyPostDetail(long id);
}

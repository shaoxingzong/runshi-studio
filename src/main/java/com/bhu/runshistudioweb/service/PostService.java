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
 * 准入规则：只有在队的工作室成员能发帖（与考勤同一套判定，
 * 认 studio_member 表而不认角色标签）；浏览帖子对游客开放。
 *
 * 先审后发：发布后一律先进「待审」，仅作者本人在「我的发帖」里可见；
 * 通过后才会出现在公开列表。因此「发出去却搜不到」是预期行为，
 * 作者要靠 listMyByPage 看状态。
 *
 * 分页参数的兜底纠正放在实现里（页码 < 1 视为 1、pageSize 收敛到上限），
 * 不抛异常：传错页码没必要让整个查询失败（与项目其它模块一致）。
 *
 * 约定：参数不合法 / 业务不允许时抛 BusinessException，不返回错误码、不返回 null。
 *
 * 管理端能力（ listForAdmin / deletePost / updatePinned /
 * updatePost）只被 AdminPostController 调用，Controller 层已有
 * @SaCheckRole(ADMIN)；Service 内仍各自校验登录态，
 * 与 audit 的做法一致——不把「调用方一定鉴权过」当作前提。
 */
public interface PostService extends IService<StudioPost> {

    /**
     * 成员发布帖子
     *
     * 三个校验顺序不能乱：
     * - 登录态 + 在队成员；
     * - 正文长度；
     * - 正文图片必须是站内路径。
     * 通过后落库为「待审」，随后由后台异步交给 AI 初判（见 ContentAuditManager）；
     * 摘要与封面为空时由服务端兜底生成。
     *
     * @param request 发布请求（标题、正文必填）
     * @return 新帖子 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、非成员、
     * 正文过长、含外链图片、入库失败时抛出
     */
    long addPost(PostAddRequest request);

    /**
     * 点赞帖子（必须登录，幂等）
     *
     * 本模块只能赞、不能取消：没有 unlike 接口，也没有 toggle。
     * 重复点赞是幂等的——直接返回当前点赞数，不会重复计数
     * （数据库唯一键 uk_post_user 在并发下兜底）。
     *
     * 只有已通过审核的帖子可赞；待审 / 驳回对公众等同于不存在，
     * 与 getApprovedDetail 同一口径（同码同提示，不暴露存在性）。
     *
     * @param userId 登录用户 ID（由 Controller 从登录态取，不接受请求体传入）
     * @param postId 被点赞的帖子 ID
     * @return 点赞后的最新点赞数（已赞过时原样返回，不重复 +1）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    Long likePost(long userId, long postId);

    /**
     * 公开列表：仅已通过，置顶优先 + 时间倒序（游客可调用）
     *
     * @param current 页码（< 1 时按 1 处理）
     * @param pageSize 每页条数（收敛到上限）
     * @return 分页结果
     */
    Page<PostFrontVO> listApprovedByPage(long current, long pageSize);

    /**
     * 公开详情：仅已通过（游客可调用），并原子自增浏览量
     *
     * @param id 帖子 ID
     * @return 详情（含 Markdown 正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    PostFrontDetailVO getApprovedDetail(long id);

    /**
     * 我的发帖：作者看自己的，含待审与已驳回（驳回时带理由）
     *
     * @param current 页码
     * @param pageSize 每页条数
     * @return 分页结果
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
    Page<PostVO> listMyByPage(long current, long pageSize);

    /**
     * 待审队列（管理员）：仅「待审」，按提交时间先进先出
     *
     * 先进先出不只是公平，更是为了避免老内容被积压——
     * 先审后发的模式下，作者提交后看不到自己的内容，
     * 若队列按其它顺序排，最早提交的可能永远沉底。
     *
     * @param current 页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    Page<PostVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * 三条约束：
     * - 驳回必须填理由——它要给作者看，没理由等于让作者猜；
     * - 只对待审内容生效——已通过/已驳回的不应被重复处理（幂等保护）；
     * - 一次 SQL 更新整批，不逐条 update。
     *
     * @param request 审核请求（ids、action、驳回理由）
     * @return 实际处理的条数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 动作非法、驳回缺理由时抛出
     */
    int audit(AuditRequest request);

    /**
     * 管理端全量列表：三种状态均可查，支持按状态 / 标题 / 作者筛选（最新提交在前）
     *
     * 与 listPendingByPage 的分工：那个是「待审队列」——只有一种状态、
     * 也不需要筛选，管理员面对的就是一条先进先出的流水。本方法服务于「帖子管理」页，
     * 已通过 / 已驳回的内容同样要能翻出来（审核只是治理的一个环节，不是全部）。
     *
     * 作者关键词匹配账号与成员姓名两处：门户展示用的是成员姓名，
     * 管理员照着屏幕上看到的名字去搜，必须能搜到——否则「搜得到展示名却查不到帖子」
     * 会让人以为数据丢了。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页）
     * @return 分页结果（含作者信息）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 审核状态取值非法时抛出
     */
    Page<PostVO> listForAdmin(PostAdminQueryRequest request);

    /**
     * 删除帖子（逻辑删除），同事务级联删除该帖的全部评论与点赞关系
     *
     * 为什么级联：只删帖子会留下两类孤儿——评论（父帖没了，却还占着审核队列）
     * 与点赞关系（还挂在已不存在的帖子上）。对齐 PostCommentService#deleteComment
     * 「连带删回复 + 同事务」的既有语义。
     *
     * 待审帖子不能删（与 deleteComment 同一条规则）：先审后发之下
     * 作者只知道「我提交了」，删掉他什么都收不到；该走驳回，让 rejectReason
     * 把理由送到作者眼前。删帖只服务于「已通过的要下线」与「已驳回的要清理」。
     *
     * 审计：studio_post 没有 deleted_by 列（评论表才有），
     * 删除前把 updated_by 显式写成操作人作为痕迹。
     *
     * @param id 帖子 ID
     * @return 本次删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在、
     * 帖子尚在待审时抛出
     */
    int deletePost(long id);

    // 置顶 / 取消置顶
    //
    // 只有已通过的帖子允许置顶：公开列表只返回已通过的内容，
    // 置顶一篇待审帖在门户上没有任何效果——属于典型误操作，
    // 因此在服务端挡掉并给出可读提示，而不是让用户点了没反应。
    //
    // 取消置顶不设状态限制：帖子被编辑后会重置为待审，但 pinned 仍为 1；
    // 若此时不许取消置顶，管理员就卡在一个「改不回来」的状态里。恢复操作永远不该被拦住。
    //
    // @param id 帖子 ID
    // @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
    // @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在、
    // 取值非法、对非已通过帖子置顶时抛出
    void updatePinned(long id, int pinned);

    // 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
    //
    // 为什么不让作者自己改：编辑权只给管理员。「先审后发」意味着内容公开前
    // 已过审，若作者能自由修改已通过的正文，等于给审核开了后门——改完的内容没人再看。
    //
    // 为什么编辑后重新送审：内容变了就必须重新过审，否则「先审后发」会被
    // 编辑功能绕开。代价是改个别字也会让文章暂时下线，因此调用方必须提示
    // 「已保存，修改内容将重新送审」。
    //
    // 编辑同时复用 addPost 的两条校验（正文长度、正文图片必须是
    // /uploads/ 站内路径）与「异步交 AI 初判」的送审逻辑。
    //
    // @param request 编辑请求（id、标题、正文必填；摘要与封面选填，为空时兜底生成）
    // @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在、
    // 正文过长、含外链图片时抛出
    void updatePost(PostUpdateRequest request);

    // 作者修改自己被驳回的帖子（改完重新送审）
    //
    // 与 updatePost（管理员代改任何帖子）共用同一套改稿实现，
    // 差别只在准入：本方法额外要求「是作者本人」且「状态为已驳回」。
    // 待审帖不许作者改（审核对象会不断变化），已通过帖更不许（公开内容
    // 作者随手改等于给审核开后门）——这两种情况都按「帖子不存在」同码返回，
    // 不暴露存在性。
    //
    // @param request 改稿请求（id、标题、正文必填；摘要与封面选填）
    // @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在、
    // 非本人、非已驳回状态、正文过长、含外链图片时抛出
    void updateMyRejectedPost(PostUpdateRequest request);

    /**
     * 管理端帖子详情（含 Markdown 正文）
     *
     * 为什么不能复用公开详情 getApprovedDetail：
     * - 它只返回已通过的帖子——待审 / 已驳回的取不到，而管理员编辑时
     * 三类状态都可能碰到；
     * - 它会原子自增浏览量——管理员点开编辑一下计数就 +1，
     * 而浏览量是内容热度的依据，不能被管理动作污染。
     *
     * 本方法不累加任何计数，纯粹是「按 id 取单篇全文」，供编辑弹窗回填。
     *
     * @param id 帖子 ID
     * @return 详情（含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在时抛出（A0402）
     */
    PostAdminDetailVO getPostForAdmin(long id);

    /**
     * 作者读自己「未公开」帖子的详情（含正文），不累加浏览量
     *
     * 为什么不能复用公开详情：getApprovedDetail 只返回已通过的帖子，
     * 而驳回稿的正文作者本来就读不到——他要改稿就必须先看到自己写了什么。
     *
     * 覆盖范围：仅本人 + 待审或已驳回。已通过的走公开详情即可。
     * 非本人、状态不符一律按「帖子不存在」返回，不暴露存在性。
     *
     * @param id 帖子 ID
     * @return 详情（含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录、帖子不存在、
     * 非本人、状态不是待审/已驳回时抛出
     */
    PostSelfDetailVO getMyPostDetail(long id);
}

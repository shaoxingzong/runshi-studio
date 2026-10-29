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
 * 业务主线是「先审后发」：成员发帖一律落「待审」→ AI 三分法初判 → 管理员人工处置 →
 * 只有「已通过」的内容对游客可见。待审与已驳回对公众等同于不存在，
 * 查询条件里就带着「状态 = 已通过」，不是查出来再过滤。
 * 因此「发出去却搜不到」是预期行为，作者要靠 listMyByPage 看状态与驳回理由。
 *
 * 准入规则：只有在队的工作室成员能发帖（认 studio_member 表而不认角色标签），浏览对游客开放。
 *
 * 管理端能力只被 AdminPostController 调用，Controller 层已有 @SaCheckRole(ADMIN)，
 * 但 Service 内仍各自校验登录态——不把「调用方一定鉴权过」当作前提。
 *
 * @author shaoshing
 */
public interface PostService extends IService<StudioPost> {

    /**
     * 成员发布帖子
     *
     * 落库时状态一律写「待审」，发布不等于公开。若这里直接写「已通过」，整个审核环节就形同虚设。
     * 准入只查登录态与 studio_member 表里的在队档案、不认角色标签——
     * 按角色放行会让「已被移出名录但角色没改」的人继续发帖。
     *
     * 坑：把「发帖成功」理解成「内容已公开」。新帖在公开列表里查不到是设计如此，
     * 要确认状态得走 listMyByPage。
     *
     * @param request 发布请求（标题、正文必填；摘要与封面选填，为空时由服务端兜底生成）。
     *                请求体里没有 status 字段，审核状态由服务端决定
     * @return 新帖子的 ID（雪花算法生成的 19 位长整型）
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、
     *         正文过长或含外链图片（A0401）、入库失败（B0001）
     */
    long addPost(PostAddRequest request);

    /**
     * 点赞帖子（必须登录，幂等）
     *
     * 本模块只能赞、不能取消，因此重复点赞必须幂等——直接返回当前点赞数，不重复计数，
     * 并发下的重复插入由唯一键 uk_post_user 兜底。只有已通过审核的帖子可赞：
     * 若对待审帖开放点赞，「能点赞成功」就泄露了「这篇其实存在」。
     *
     * 坑：计数必须用 SQL 表达式自增（like_count = like_count + 1），
     * 不能「读出来 +1 再写回」，后者在并发下两个请求会互相覆盖，计数偏小。
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
     * 筛选条件必须落在 SQL 的 WHERE 里而不是查出来再在内存里过滤——后者会让分页总数
     * 把未过审的内容也算进去，出现「一共 10 条却只显示 6 条」。置顶压过时间：
     * 置顶是运营手段，必须能突破「新内容排前面」的默认规则。
     *
     * 坑：作者名与 liked 必须批量查（各一次 IN），不能对每条帖子各查一次。
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
     * 详情比列表多一列正文，也多了「浏览」这个副作用，因此与列表分成两个接口。
     * 浏览量的自增用 SQL 表达式完成，读与写合成一条语句，不存在并发覆盖问题。
     *
     * 坑：待审 / 已驳回的帖子必须与「不存在」返回同样的错误码与提示，
     * 单独提示等于告诉外人「这篇确实存在，只是没过审」。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含 Markdown 正文）
     * @throws BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    PostFrontDetailVO getApprovedDetail(long id);

    /**
     * 我的发帖：作者看自己的，含待审与已驳回（驳回时带理由）
     *
     * 这是「先审后发」必须配套的出口：作者看不到状态只会认为「发失败了」。
     * 因此刻意不加状态过滤，过滤条件换成 author_id = 当前登录人。
     *
     * 坑：不要为了省一次查询复用公开列表的查询条件——公开列表带 status = 已通过，
     * 作者自己的待审稿会被过滤掉，「我的发帖」永远是空的。
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
     * 按创建时间升序，与「管理端全量列表」的倒序相反：那个是翻查最近发生了什么，
     * 这个是排队作业。先进先出不只是公平，更是为了避免老内容被积压。
     *
     * 坑：本方法不校验登录态与管理员角色，这两道在 Controller 上由 @SaCheckRole 拦下。
     * 若挪给普通用户调用，会把全站待审内容连同作者名一起暴露出去。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * 管理员面对的是队列而不是单条内容，因此一次传一批 id、一条 UPDATE 处理整批。
     * 更新条件里必须带「状态 = 待审」：已通过 / 已驳回的记录不该被重复处理，
     * 否则后一次审核会覆盖前一次的结论。驳回必须填理由——理由要给作者看。
     *
     * 坑：不要为了「返回处理条数」把 this.update 的布尔值当成条数，它只表示成功与否。
     * 要条数必须用 baseMapper.update 的返回值（受影响行数）。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、
     *                驳回时 rejectReason 必填且不超过 256 字）
     * @return 实际处理的条数（已在审核前被处理过的不计入，可能小于传入的 id 个数）
     * @throws BusinessException 未登录（A0201）、动作非法或驳回缺理由（A0401）
     */
    int audit(AuditRequest request);

    /**
     * 管理端全量列表：三种状态均可查，支持按状态 / 标题 / 作者筛选（最新提交在前）
     *
     * 与 listPendingByPage 的分工是「队列」与「台账」：已通过 / 已驳回的内容同样要能翻出来。
     * 作者关键词要同时匹配账号与成员姓名两处——门户展示用的是成员姓名，
     * 管理员照着屏幕上的名字去搜必须能搜到。
     *
     * 坑：不传某个条件表示「不按它筛选」，而不是「查该字段为 null 的记录」。
     * 把 null 也拼进 SQL，管理端默认打开时会看到一张空表。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页），不能为 null
     * @return 分页结果（含作者展示名）；筛选不到任何内容时返回空记录分页
     * @throws BusinessException 查询参数为 null、审核状态取值非法（A0401）
     */
    Page<PostVO> listForAdmin(PostAdminQueryRequest request);

    /**
     * 删除帖子（逻辑删除），同事务级联删除该帖的全部评论与点赞关系
     *
     * 只删帖子会留下两类孤儿——评论（还占着审核队列）与点赞关系（还挂在已不存在的帖子上），
     * 因此三处写必须在同一事务里完成，且帖子必须最后删。
     *
     * 坑：待审帖子不能删。先审后发之下作者只知道「我提交了」，直接删掉他什么都收不到；
     * 该走驳回，让 rejectReason 把理由送到作者眼前。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、帖子尚在待审（A0401）
     */
    int deletePost(long id);

    /**
     * 置顶 / 取消置顶
     *
     * 只有已通过的帖子允许置顶——公开列表只返回已通过的内容，置顶一篇待审帖在门户上
     * 没有任何效果，属于典型误操作。取消置顶则刻意不设状态限制：帖子被编辑后会重置为待审，
     * 但 pinned 仍为 1，若此时不许取消，管理员就卡在「改不回来」的状态里。
     * 恢复操作永远不该被拦住，这是本方法唯一的不对称之处。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、
     *         取值非法或对非已通过帖子置顶（A0401）
     */
    void updatePinned(long id, int pinned);

    /**
     * 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
     *
     * 编辑权只给管理员：若作者能自由修改已通过的正文，等于给审核开了后门——
     * 改完的内容没人再看。同理，编辑后必须重新送审，否则「先审后发」会被编辑功能绕开。
     * 代价是改个别字也会让文章暂时下线，调用方必须提示「已保存，修改内容将重新送审」。
     *
     * 坑：本方法不校验管理员角色，这道在 Controller 的 @SaCheckRole(ADMIN) 上。
     * 若暴露给普通用户，就等于把「绕过审核改内容」做成一个接口。
     *
     * @param request 编辑请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）、正文过长或含外链图片（A0401）
     */
    void updatePost(PostUpdateRequest request);

    /**
     * 作者修改自己被驳回的帖子（改完重新送审）
     *
     * 与 updatePost 共用同一套改稿实现，差别只在准入：本方法额外要求「是作者本人」
     * 且「状态为已驳回」。待审帖不许作者改（审核对象会不断变化），已通过帖更不许。
     * 这两种情况都按「帖子不存在」同码返回，不暴露存在性。
     *
     * 坑：只判本人不判状态，作者就能修改已通过的正文；只判状态不判本人，
     * 任何人都能改别人的驳回稿。
     *
     * @param request 改稿请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws BusinessException 未登录（A0201）、帖子不存在 / 非本人 / 状态不是已驳回（均 A0402）、
     *         正文过长或含外链图片（A0401）
     */
    void updateMyRejectedPost(PostUpdateRequest request);

    /**
     * 管理端帖子详情（含 Markdown 正文）
     *
     * 不能复用公开详情：后者只返回已通过的帖子，而管理员编辑时三类状态都可能碰到；
     * 它还会原子自增浏览量，管理员点开编辑一下计数就 +1，而浏览量不能被管理动作污染。
     * 本方法不累加任何计数，纯粹是「按 id 取单篇全文」，供编辑弹窗回填。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文），三种审核状态都会返回
     * @throws BusinessException 未登录（A0201）、帖子不存在（A0402）
     */
    PostAdminDetailVO getPostForAdmin(long id);

    /**
     * 作者读自己「未公开」帖子的详情（含正文），不累加浏览量
     *
     * 覆盖范围刻意收窄到「仅本人 + 待审或已驳回」：已通过的走公开详情即可，
     * 少一条能读到公开内容的私有路径，就少一处需要维护的边界。
     * 非本人、状态不符一律按「帖子不存在」返回，不暴露存在性。
     *
     * 坑：这里也不能顺手加浏览量自增——作者反复打开自己的草稿不该被算成「有人看过」。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文）
     * @throws BusinessException 未登录（A0201）、帖子不存在 / 非本人 / 状态不是待审或已驳回（均 A0402）
     */
    PostSelfDetailVO getMyPostDetail(long id);
}

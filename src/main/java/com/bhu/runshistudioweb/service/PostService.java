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
 * 业务主线是「先审后发」：成员发帖 → 一律落「待审」→ AI 先做三分法初判
 * （安全直接通过 / 违规直接驳回 / 灰色转人工）→ 管理员在审核队列里人工处置 →
 * 只有「已通过」的内容对游客可见。待审与已驳回对公众等同于不存在，
 * 查询条件里就带着「状态 = 已通过」，不是查出来再过滤。
 *
 * 因此「发出去却搜不到」是预期行为，作者要靠 listMyByPage 看状态与驳回理由。
 *
 * 准入规则：只有在队的工作室成员能发帖（与考勤同一套判定，
 * 认 studio_member 表而不认角色标签）；浏览帖子对游客开放。
 *
 * 分页参数的兜底纠正放在实现里（页码小于 1 视为 1、pageSize 收敛到上限），
 * 不抛异常：传错页码没必要让整个查询失败（与项目其它模块一致）。
 *
 * 约定：参数不合法 / 业务不允许时抛 BusinessException，不返回错误码、不返回 null。
 *
 * 管理端能力（ listForAdmin / deletePost / updatePinned /
 * updatePost）只被 AdminPostController 调用，Controller 层已有
 * @SaCheckRole(ADMIN)；Service 内仍各自校验登录态，
 * 与 audit 的做法一致——不把「调用方一定鉴权过」当作前提。
 *
 * @author shaoshing
 */
public interface PostService extends IService<StudioPost> {

    /**
     * 成员发布帖子
     *
     * <p>为什么这样设计：这是「先审后发」的入口，发布不等于公开。
     * 落库时状态一律写「待审」，作者立刻拿到帖子 ID，内容能否公开由后面的
     * AI 初判与人工审核决定。若这里直接写「已通过」，整个审核环节就形同虚设。
     * 准入只查登录态与 studio_member 表里的在队档案、不认角色标签：
     * 成员身份是管理员维护的档案，角色只是权限标签，两者可能不同步——
     * 按角色放行会让「已被移出名录但角色没改」的人继续发帖。
     * 摘要与封面为空时由服务端兜底生成，所以调用方不必强制用户填。
     *
     * <p>执行步骤（实现里的 ①②③ 标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态——不知道「谁发的」，后面所有步骤都无从谈起，必须最先挡；</li>
     *     <li>校验在队成员身份——认 studio_member 表，与考勤同一套判定；</li>
     *     <li>校验正文长度——排在内容清洗之前，先用一个常数挡掉超大输入；</li>
     *     <li>清洗正文并校验图片——后续每一步看到的都必须是清洗后的内容，顺序不能换；</li>
     *     <li>落库为「待审」——先审后发的核心约定；</li>
     *     <li>异步送 AI 初判——不阻塞提交，用户提交完就能离开页面。</li>
     * </ol>
     *
     * <p>容易踩的坑：把「发帖成功」理解成「内容已公开」。新帖在公开列表里查不到
     * 是设计如此；要确认状态得走 listMyByPage，否则会误以为接口没写进去。
     *
     * @param request 发布请求（标题、正文必填；摘要与封面选填，为空时由服务端兜底生成）。
     * 请求体里没有 status 字段——审核状态由服务端决定，传进来也不会被采用
     * @return 新帖子的 ID（雪花算法生成的 19 位长整型，不是数据库自增主键）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 非在队成员（A0301）、正文过长或含外链图片（A0401）、入库失败（B0001）时抛出
     */
    long addPost(PostAddRequest request);

    /**
     * 点赞帖子（必须登录，幂等）
     *
     * <p>为什么这样设计：本模块只能赞、不能取消，没有 unlike 接口，也没有 toggle。
     * 正因为没有「取消」，重复点赞必须幂等（幂等：重复调用与调用一次的结果一致）——
     * 直接返回当前点赞数，不会重复计数，并发下的重复插入由数据库唯一键
     * uk_post_user 兜底。只有已通过审核的帖子可赞：待审 / 驳回对公众等同于不存在，
     * 若对它们开放点赞，等于用「能点赞成功」泄露了「这篇其实存在」。
     *
     * <p>执行步骤（实现里的 ①②③ 标注与下面一一对应）：
     * <ol>
     *     <li>确认帖子存在且已通过——先确认对象可赞，否则会给不存在的帖子留下点赞行；</li>
     *     <li>查重：已赞过就原样返回当前计数，一次写都不做；</li>
     *     <li>插入点赞关系行 + 原子自增计数，两步在同一事务内，要么都成功要么都回滚。</li>
     * </ol>
     *
     * <p>容易踩的坑：计数必须用 SQL 表达式自增（like_count = like_count + 1），
     * 不能「读出来 +1 再写回」——后者在并发下两个请求会互相覆盖，计数偏小。
     *
     * @param userId 登录用户 ID（由 Controller 从登录态取，不接受请求体传入，
     * 否则「替别人点赞」就成了一个功能）
     * @param postId 被点赞的帖子 ID，必须大于 0，否则抛 A0401
     * @return 点赞后的最新点赞数（已赞过时原样返回，不重复 +1；计数列为空时按 0 返回，
     * 调用方不必判空）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    Long likePost(long userId, long postId);

    /**
     * 公开列表：仅已通过，置顶优先 + 时间倒序（游客可调用）
     *
     * <p>为什么这样设计：公开列表是「先审后发」的出口，筛选条件必须落在 SQL 的
     * WHERE 里（status = 已通过），而不是查出来再在内存里过滤——
     * 后者会让分页总数把未过审的内容也算进去，出现「一共 10 条却只显示 6 条」。
     * 排序上置顶压过时间：置顶是运营手段，必须能突破「新内容排前面」的默认规则。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>按「状态 = 已通过」分页查询，置顶优先再按创建时间倒序；</li>
     *     <li>批量查作者名——一次 IN 查询，不能对每条帖子各查一次；</li>
     *     <li>批量查「当前用户赞了这页里的哪几篇」，同样一次查完；</li>
     *     <li>在内存里把作者名与 liked 填进 VO，再组装成 VO 分页。</li>
     * </ol>
     *
     * <p>容易踩的坑：N+1 查询（查 1 次列表，再对每条记录各查 1 次，条数越多查询越多）。
     * 一页 10 条就是 20 次额外查询，量上来后列表接口会先于其它接口变慢。
     *
     * @param current 页码，小于 1 时按 1 处理（不报错，页码传错不该让查询失败）
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限（防一次拉全表）
     * @return 分页结果；没有已通过的帖子时返回空记录的分页对象，不会返回 null
     */
    Page<PostFrontVO> listApprovedByPage(long current, long pageSize);

    /**
     * 公开详情：仅已通过（游客可调用），并原子自增浏览量
     *
     * <p>为什么这样设计：详情比列表多一列正文，也多了「浏览」这个副作用，
     * 所以它与列表分成两个接口：列表不带正文（一页十条全文会把响应体撑大），
     * 详情才取正文。浏览量的自增用 SQL 表达式完成，读与写合成一条语句，
     * 不存在「先读到 10、两个请求都写回 11」的覆盖问题。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>按 id + 状态 = 已通过取单篇——状态条件必须和查询绑在一起；</li>
     *     <li>取不到就按「不存在」报错，不区分「没这篇」与「没过审」；</li>
     *     <li>原子自增浏览量；</li>
     *     <li>填作者名与 liked，组装详情 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：待审 / 已驳回的帖子必须与「不存在」返回同样的错误码与提示。
     * 若给它们单独的错误提示，就等于告诉外人「这篇确实存在，只是没过审」。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含 Markdown 正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 帖子不存在或尚未通过审核时抛出（A0402）
     */
    PostFrontDetailVO getApprovedDetail(long id);

    /**
     * 我的发帖：作者看自己的，含待审与已驳回（驳回时带理由）
     *
     * <p>为什么这样设计：这是「先审后发」必须配套的出口。内容提交后不会出现在
     * 公开列表里，如果作者也没有地方看状态，他只会认为「发失败了」。
     * 因此本方法刻意不加状态过滤——作者自己的三种状态都要能看到，
     * 尤其是已驳回要带上理由，他才知道该改哪里。
     * 过滤条件换成 author_id = 当前登录人，把「看谁的」限定在自己身上。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态——不登录就没有「我的」这个主体；</li>
     *     <li>按 author_id 分页查询，时间倒序（作者关心的是最近发的）；</li>
     *     <li>转成 VO 并批量填作者名，与其它列表接口保持同一契约。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「省一次查询」复用公开列表的查询条件。
     * 公开列表带 status = 已通过，作者自己的待审稿会被过滤掉，
     * 于是「我的发帖」永远是空的。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果（含状态、驳回理由、AI 初判结论）；该作者没有帖子时返回空记录分页
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛出（A0201）
     */
    Page<PostVO> listMyByPage(long current, long pageSize);

    /**
     * 待审队列（管理员）：仅「待审」，按提交时间先进先出
     *
     * <p>为什么这样设计：先进先出不只是公平，更是为了避免老内容被积压——
     * 先审后发的模式下，作者提交后看不到自己的内容，
     * 若队列按其它顺序排，最早提交的可能永远沉底。
     * 因此这里按创建时间升序（orderByAsc），与「管理端全量列表」的倒序相反：
     * 那个是翻查最近发生了什么，这个是排队作业，两者排序方向本就该不同。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>按「状态 = 待审」分页查询，创建时间升序；</li>
     *     <li>转成 VO 并批量填作者名。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验登录态与管理员角色——这两道在 Controller 上
     * 由 @SaCheckRole 拦下。若把本方法挪给普通用户调用，就会把全站的待审内容
     * 连同作者名一起暴露出去。
     *
     * @param current 页码，小于 1 时按 1 处理
     * @param pageSize 每页条数，小于 1 按默认值、超过上限收敛到上限
     * @return 分页结果；队列为空时返回空记录分页，不会返回 null
     */
    Page<PostVO> listPendingByPage(long current, long pageSize);

    /**
     * 批量审核（管理员）：通过或驳回
     *
     * <p>为什么这样设计：管理员面对的是队列而不是单条内容，逐条点开会把时间
     * 耗在页面切换上，所以一次传一批 id、一条 UPDATE 处理整批。
     * 更新条件里必须带「状态 = 待审」：已通过 / 已驳回的记录不该被重复处理，
     * 否则后一次审核会覆盖掉前一次的结论（这正是幂等保护）。
     * 驳回必须填理由——理由要给作者看，没理由等于让作者猜。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态与动作取值，驳回时再校验理由非空；</li>
     *     <li>按「id 在批次内 + 状态 = 待审」查出真正会被处理的帖子，
     *     顺序在更新之前——一条 UPDATE 拿不到作者，通知必须靠这份名单；</li>
     *     <li>一条 UPDATE 更新整批，并拿到实际影响的行数；</li>
     *     <li>逐个给作者发通知，放在更新之后：审核失败会抛异常，自然走不到通知。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要为了「返回处理条数」把 this.update 的布尔值当成条数——
     * 它只表示成功与否。要条数必须用 baseMapper.update 的返回值（受影响行数）。
     *
     * @param request 审核请求（ids 至少一个、action 只能是 approve 或 reject、
     * 驳回时 rejectReason 必填且不超过 256 字）
     * @return 实际处理的条数（已在审核前被处理过的不计入，因此可能小于传入的 id 个数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 动作非法或驳回缺理由（A0401）时抛出
     */
    int audit(AuditRequest request);

    /**
     * 管理端全量列表：三种状态均可查，支持按状态 / 标题 / 作者筛选（最新提交在前）
     *
     * <p>为什么这样设计：与 listPendingByPage 的分工是「队列」与「台账」——
     * 那个只有一种状态、也不需要筛选，管理员面对的就是一条先进先出的流水；
     * 本方法服务于「帖子管理」页，已通过 / 已驳回的内容同样要能翻出来
     * （审核只是治理的一个环节，不是全部）。
     * 作者关键词要同时匹配账号与成员姓名两处：门户展示用的是成员姓名，
     * 管理员照着屏幕上看到的名字去搜必须能搜到——否则「搜得到展示名却查不到帖子」
     * 会让人以为数据丢了。
     *
     * <p>执行步骤（实现里的 ①②③ 标注与下面一一对应）：
     * <ol>
     *     <li>校验审核状态取值——GET 参数不做注解校验，非法值必须在这里挡住；</li>
     *     <li>把作者关键词解析成一批 userId 再按 author_id IN 过滤，
     *     解析不到任何人时直接返回空页，不必再查帖子；</li>
     *     <li>按创建时间倒序分页查询并转成 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：不传某个条件表示「不按它筛选」，而不是「查该字段为 null 的记录」。
     * 若把 null 也拼进 SQL，管理端默认打开时会看到一张空表。
     *
     * @param request 查询条件（status / titleKeyword / authorKeyword / 分页），不能为 null
     * @return 分页结果（含作者展示名）；筛选不到任何内容时返回空记录分页
     * @throws com.bhu.runshistudioweb.exception.BusinessException 查询参数为 null、
     * 审核状态取值非法时抛出（A0401）
     */
    Page<PostVO> listForAdmin(PostAdminQueryRequest request);

    /**
     * 删除帖子（逻辑删除），同事务级联删除该帖的全部评论与点赞关系
     *
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里，
     * 查询时由框架自动追加 deleted_at = 0 过滤）能保住审计线索，所以帖子本身
     * 不物理删除。但只删帖子会留下两类孤儿——评论（父帖没了，却还占着审核队列）
     * 与点赞关系（还挂在已不存在的帖子上），因此三处写必须在同一事务里完成。
     *
     * <p>执行步骤（实现里的 ①②③④ 标注与下面一一对应）：
     * <ol>
     *     <li>先把 updated_by 写成操作人——本表没有 deleted_by 列，这是唯一的操作痕迹；</li>
     *     <li>级联删除该帖全部评论（含楼中楼回复）；</li>
     *     <li>删除该帖全部点赞关系（关系表是物理删除）；</li>
     *     <li>最后删帖子本体——顺序上帖子必须最后删，否则中途失败会留下「父帖没了、子行还在」。</li>
     * </ol>
     *
     * <p>容易踩的坑：待审帖子不能删（与删除评论同一条规则）。先审后发之下
     * 作者只知道「我提交了」，直接删掉他什么都收不到；该走驳回，
     * 让 rejectReason 把理由送到作者眼前。删帖只服务于「已通过的要下线」
     * 与「已驳回的要清理」两种场景。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 删除的内容行数（帖子 1 + 其下评论数；点赞关系是物理删除，不计入）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、帖子尚在待审（A0401）时抛出
     */
    int deletePost(long id);

    /**
     * 置顶 / 取消置顶
     *
     * <p>为什么这样设计：只有已通过的帖子允许置顶——公开列表只返回已通过的内容，
     * 置顶一篇待审帖在门户上没有任何效果，属于典型误操作，
     * 因此在服务端挡掉并给出可读提示，而不是让用户点了没反应。
     * 取消置顶则刻意不设状态限制：帖子被编辑后会重置为待审，但 pinned 仍为 1；
     * 若此时不许取消置顶，管理员就卡在一个「改不回来」的状态里。
     * 恢复操作永远不该被拦住，这是本方法唯一的不对称之处。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态与 id、pinned 取值——pinned 只接受 0 与 1；</li>
     *     <li>取出帖子，不存在则报 A0402；</li>
     *     <li>置顶时再校验状态必须是已通过（取消置顶跳过这一步）；</li>
     *     <li>按 id 更新 pinned，更新失败抛 B0002。</li>
     * </ol>
     *
     * <p>容易踩的坑：把状态校验写成「无条件要求已通过」，
     * 会让被编辑过的帖子既不能置顶也不能取消置顶，等于把数据锁死。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @param pinned 1-置顶, 0-取消置顶（其它值拒绝）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、取值非法或对非已通过帖子置顶（A0401）时抛出
     */
    void updatePinned(long id, int pinned);

    /**
     * 编辑帖子（仅管理员），保存后非待审状态一律重置为待审重新送审
     *
     * <p>为什么这样设计：编辑权只给管理员。「先审后发」意味着内容公开前
     * 已过审，若作者能自由修改已通过的正文，等于给审核开了后门——改完的内容没人再看。
     * 同理，编辑后必须重新送审：内容变了就要重新过审，否则「先审后发」会被
     * 编辑功能绕开。代价是改个别字也会让文章暂时下线，因此调用方必须提示
     * 「已保存，修改内容将重新送审」。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态与请求体非空；</li>
     *     <li>取出帖子，不存在则报 A0402；</li>
     *     <li>走公共改稿逻辑：校验正文、重置待审、清空上一轮审核痕迹、重新送 AI 初判。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不校验管理员角色——这道在 Controller 的
     * @SaCheckRole(ADMIN) 上。若把本方法暴露给普通用户，就等于把「绕过审核改内容」
     * 做成一个接口。
     *
     * @param request 编辑请求（id、标题、正文必填；摘要与封面选填，为空时兜底生成），不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在（A0402）、正文过长或含外链图片（A0401）时抛出
     */
    void updatePost(PostUpdateRequest request);

    /**
     * 作者修改自己被驳回的帖子（改完重新送审）
     *
     * <p>为什么这样设计：与 updatePost（管理员代改任何帖子）共用同一套改稿实现，
     * 差别只在准入：本方法额外要求「是作者本人」且「状态为已驳回」。
     * 待审帖不许作者改（审核对象会不断变化，管理员可能正看着修改前的内容），
     * 已通过帖更不许（公开内容作者随手改等于给审核开后门）——
     * 这两种情况都按「帖子不存在」同码返回，不暴露存在性。
     *
     * <p>执行步骤（实现里的 ①② 标注与下面一一对应）：
     * <ol>
     *     <li>校验作者本人——同码同提示，不让外人探知「这篇存在但不是你的」；</li>
     *     <li>校验状态为已驳回——其余状态一律按不存在处理；</li>
     *     <li>走公共改稿逻辑：重置待审、清空审核痕迹、重新送 AI 初判。</li>
     * </ol>
     *
     * <p>容易踩的坑：只判「作者是不是本人」而不判状态，作者就能修改已通过的正文；
     * 反过来只判状态不判本人，任何人都能改别人的驳回稿。
     *
     * @param request 改稿请求（id、标题、正文必填；摘要与封面选填），不能为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是已驳回（均 A0402）、正文过长或含外链图片（A0401）时抛出
     */
    void updateMyRejectedPost(PostUpdateRequest request);

    /**
     * 管理端帖子详情（含 Markdown 正文）
     *
     * <p>为什么这样设计：不能复用公开详情 getApprovedDetail，两个理由——
     * 它只返回已通过的帖子，待审 / 已驳回的取不到，而管理员编辑时三类状态都可能碰到；
     * 它会原子自增浏览量，管理员点开编辑一下计数就 +1，
     * 而浏览量是内容热度的依据，不能被管理动作污染。
     * 本方法不累加任何计数，纯粹是「按 id 取单篇全文」，供编辑弹窗回填。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态——不把「Controller 一定鉴权过」当作前提，这是第二道；</li>
     *     <li>按 id 取单篇，不看审核状态；</li>
     *     <li>转成管理端 VO 并填作者展示名。</li>
     * </ol>
     *
     * <p>容易踩的坑：顺手在这里加一句浏览量自增，会让管理员每打开一次编辑弹窗
     * 就给内容热度灌一次水，而且这种污染很难从数据上看出来。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文），三种审核状态都会返回
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在时抛出（A0402）
     */
    PostAdminDetailVO getPostForAdmin(long id);

    /**
     * 作者读自己「未公开」帖子的详情（含正文），不累加浏览量
     *
     * <p>为什么这样设计：不能复用公开详情——getApprovedDetail 只返回已通过的帖子，
     * 而驳回稿的正文作者本来就读不到，他要改稿就必须先看到自己写了什么。
     * 覆盖范围刻意收窄到「仅本人 + 待审或已驳回」：已通过的走公开详情即可，
     * 少一条能读到公开内容的私有路径，就少一处需要维护的边界。
     * 非本人、状态不符一律按「帖子不存在」返回，不暴露存在性。
     *
     * <p>执行步骤（实现里的标注与下面一一对应）：
     * <ol>
     *     <li>校验登录态并取当前用户 ID；</li>
     *     <li>按 id 取帖子，不存在报 A0402；</li>
     *     <li>校验作者是本人——与 updateMyRejectedPost 同一条边界；</li>
     *     <li>校验状态是待审或已驳回，其余按不存在处理；</li>
     *     <li>组装 VO 并带上正文。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里也不能顺手加浏览量自增——作者反复打开自己的草稿
     * 不该被算成「有人看过」。
     *
     * @param id 帖子 ID，必须大于 0，否则抛 A0401
     * @return 详情（含正文）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 帖子不存在 / 非本人 / 状态不是待审或已驳回（均 A0402）时抛出
     */
    PostSelfDetailVO getMyPostDetail(long id);
}

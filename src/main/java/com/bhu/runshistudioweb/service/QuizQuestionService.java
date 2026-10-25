package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterDeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterMoveRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterRenameRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionAddRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionImportRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionQueryRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionUpdateRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizTextbookRenameRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizImportResultVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizTextbookVO;

import java.util.List;

/**
 * 刷题题库服务（管理端为主，另含 C 端的教材/章节树）
 *
 * <p>本接口是题库读写的契约，实现类只解释「为什么这么实现」。
 * 调用方分两类：C 端只用到 listCatalog 一个方法，其余全部是管理端接口，
 * 由 Controller 上的 {@code @SaCheckRole("admin")} 把关——本接口<b>不做</b>角色校验，
 * 谁调用它谁负责把门，这一点与考勤、刷题两个模块（在实现里查成员表）不同。
 *
 * <p>为什么 C 端的教材/章节树也在这里：它读的是同一张题目表、
 * 用的是同一条 GROUP BY。放到 PracticeService 里会让「谁负责读题库」
 * 这个边界变模糊——本类负责「题库的读与写」，PracticeService 负责
 * 「出题、判分、错题本」，职责按数据而不是按端划分。
 *
 * <p>教材与章节不是独立实体，因此这里没有 addChapter / deleteTextbook
 * 这类「新建一个空章节」的方法。章节随题目诞生，能做的只有
 * 「改名 / 调序 / 移章 / 整章删除」这四种批量操作。
 *
 * <p>写操作的三条共同纪律：
 * <ul>
 *     <li>新增 / 修改 / 导入共用同一套跨字段校验（单选恰好 1 个正确项、
 *     多选至少 2 个、标签从 A 连续、正确项属于选项）。三条入口各写一套的话，
 *     迟早出现「导入能进、编辑不让改」这种不报错、只让人困惑的问题；</li>
 *     <li>修改是「部分更新」：字段为 null 表示不改，但校验时要用旧值补齐后再整体校验
 *     （否则「只改解析」会因缺选项而报错）；</li>
 *     <li>判定相关变更（改答案 / 改题型 / 增删选项）要重置该题所有用户错题条目的
 *     连对进度，但保留 status——已消解的不退回待复习。用户确实掌握过这件事仍然成立，
 *     管理员改题不该否定它；作废的只是「连对几次」这份证据。</li>
 * </ul>
 *
 * <p>约定：参数不合法 / 业务不允许时一律抛 BusinessException，
 * 不返回错误码、不返回 null。
 *
 * @author shaoshing
 */
public interface QuizQuestionService extends IService<StudioQuizQuestion> {

    // ==================== C 端：教材 / 章节树 ====================

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * <p>为什么这样设计：教材与章节不是独立实体，它们是题目表上的两个名字列
     * （外加各自的排序值）。因此「有哪些教材、每本有哪些章节」只能从题目表里聚合出来，
     * 也就不存在「新建一个空章节」这种操作——章节随题目诞生。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 一条 GROUP BY textbook_name, chapter_name 同时取出教材名、章节名、
     *     各自的排序值与题数。排序值取该组的最小值，含义是「这一章声明过的最靠前位置」；</li>
     *     <li>② 在内存里按教材聚合：同一本教材会出现多行（一章一行），
     *     每本只建一个对象，题数随行累加；</li>
     *     <li>③ 章节的唯一键用「教材名 + 章节名」：不同教材可以有同名章节，
     *     只用章节名做键会把两本书的「第一章」合并成一个；</li>
     *     <li>④ 全部收集完之后统一排序（教材按排序值、名称，章节同理），
     *     排序放在最后是因为一本教材的排序值要等它所有章节都见过才确定。</li>
     * </ol>
     *
     * <p>容易踩的坑：只统计<b>启用</b>的题。用户看到的数字是「这一章有多少题可刷」，
     * 把停用题算进去会让用户点进去发现题数对不上，而这不会报错、只会让人以为系统丢题。
     * 另一个坑是排序值可能为 null，比较器必须处理 null，否则目录接口会直接 NPE。
     * 教材名或章节名为 null 的行会被跳过（来自手工改库的脏数据）——
     * 跳过比让整个目录接口报错合适，但这也意味着「有题却不在目录里」，
     * 排查时先确认题目上这两个字段是否为空。
     *
     * <p>本方法<b>不做成员校验</b>：它同时服务管理端与 C 端，
     * C 端入口必须先调 QuizPracticeService#requireActiveMemberId，
     * 否则「只有账号、没有成员档案」的普通用户也能读到整个题库目录。
     *
     * @return 教材列表（按教材排序值、名称升序），每本含其章节（按章节排序值、名称升序）；
     *         题库为空时返回空列表，不返回 null
     */
    List<QuizTextbookVO> listCatalog();

    // ==================== 管理端：题目读写 ====================

    /**
     * 管理端分页查询题目（含选项与答案、作答数与正确率）
     *
     * <p>为什么这样设计：管理端要看的是「存量与质量」，因此这个接口会带上正确答案与解析
     * （供编辑回填），也会带上每题的作答数与正确率（供判断题目难度是否合理）。
     * 作答统计由一次 GROUP BY question_id 批量填充，不是逐行 count——
     * 逐行统计在每页 20 条时就是 20 次查询，即 N+1（一次主查询引发 N 次子查询）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时兜一个空对象：不传任何条件等于无条件查第一页，是合理用法；</li>
     *     <li>② 先校验题型与状态。两者都是闭集，非法取值必须报错，
     *     而不是静默返回空列表——静默返回空会把排查方向带偏，
     *     管理员会以为「这题被删了」，实际是参数传错了；</li>
     *     <li>③ 拼查询条件：教材、章节、题型、状态用等值匹配（走索引），
     *     关键词只模糊匹配题干（搜解析会让「解析里提过某个词」的题也混进来）；</li>
     *     <li>④ 按「教材 → 章节 → 题序 → ID」排序，让同一章的题连在一起，
     *     管理员翻页时看到的顺序与录入顺序一致；</li>
     *     <li>⑤ 分页必须发生在数据库侧，不能把所有题查出来再在内存里切页；</li>
     *     <li>⑥ 一次 GROUP BY 填完作答数与正确率，查询次数与页大小无关；</li>
     *     <li>⑦ 组装 VO 分页对象，把 current / size / total 一并带过去。</li>
     * </ol>
     *
     * <p>容易踩的坑：正确率的分母为 0 时返回 null 而不是 0——0% 读起来像「全错」，
     * 那是错误的信息。另外关键词没有转义 % 与 _，搜「100%」会匹配到多余结果，
     * 这是刻意接受的（题干里出现通配符的概率极低）。
     *
     * @param request 查询条件，允许整体为 null（表示无条件查第一页）；
     *                字段为 null 表示「该条件不参与筛选」，不是「查 null 值」
     * @return 分页结果，记录里含选项、正确答案与解析；
     *         没有匹配数据时 records 为空列表（不是 null），total 为 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题型或状态取值非法时抛 A0401
     */
    Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request);

    /**
     * 管理端按 id 查题目详情（含选项与答案）
     *
     * <p>为什么这样设计：编辑题目时需要回填完整内容，包括正确答案与解析，
     * 因此这个接口会下发答案——它只对管理员开放，C 端走的是另一个 VO
     * （QuizQuestionVO，结构上没有答案字段）。与列表接口共用同一套 VO 转换与统计填充，
     * 保证「列表里看到的」与「详情里看到的」是同一套字段。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先校验 id 为正：负数与 0 不可能存在，早点拒绝省一次查询；</li>
     *     <li>② 查实体并判空。逻辑删除的题查不到，此时给「不存在」而不是空对象，
     *     前端才能明确提示；</li>
     *     <li>③ 转 VO 后填充作答统计，复用与列表同一个批量方法，
     *     比另写一套单条统计更不容易出现口径差异。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不做数据权限校验（比如「只能看自己录的题」），
     * 因为题库是公共资产，管理端角色已经是最小权限单位。
     * 若将来要支持多租户，必须在最前面加一层归属判断。
     *
     * @param id 题目 ID，必须大于 0（小于等于 0 抛 A0401）
     * @return 题目详情，含选项、正确答案与解析；永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法时抛 A0401；
     *         题目不存在（含已逻辑删除）时抛 A0402
     */
    QuizQuestionAdminVO getQuestionById(long id);

    /**
     * 新增题目
     *
     * <p>为什么这样设计：题目有四个互相牵制的字段（题型、选项、正确答案、解析），
     * 单独校验任何一个都拦不住错误——「单选却有 2 个正确项」「正确答案指向不存在的选项」
     * 这类问题只能把字段放在一起看。因此校验全部走同一套跨字段规则，
     * 新增 / 修改 / 导入三条入口共用，任何一条分叉都会出现「导入能进、编辑不让改」
     * 这类难查的问题。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 跨字段校验 + 规范化：选项标签统一成大写、正确项统一成
     *     「大写 + 去重 + 升序 + 无分隔」，非法输入在这一步就被拒；</li>
     *     <li>③ 组装实体并落库，选项序列化成 JSON 字符串（列类型是 text）；</li>
     *     <li>④ 返回新题目的 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：新题一律以「启用」状态入库，DTO 里没有 status 字段——
     * 若允许新增时指定停用，管理员会得到一道「建好了但谁也刷不到」的题，
     * 而界面不会提示。需要停用时走 updateStatus。
     * 另外本方法带事务，但只有一次写；加事务是为了与后续可能追加的
     * 「同事务重置错题进度」保持一致，不是为了这一次 insert。
     *
     * @param request 新增请求，不能为 null；教材名、章节名、题干、题型、选项、
     *                正确答案必填；选项 2~6 个（多选至少 3 个），标签必须从 A 连续
     * @return 新题目 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 字段不合法或跨字段规则不满足
     *         （如单选题 2 个正确项、正确项指向不存在的选项）时抛 A0401；
     *         入库失败时抛 B0002
     */
    long addQuestion(QuizQuestionAddRequest request);

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * <p>为什么这样设计：部分更新让前端「只改解析」时不必回传整道题。
     * 但校验规则是跨字段的，只拿请求里传了的字段去校验，「只改解析」会因为没传选项
     * 而报「选项不能为空」——这是纯粹的误伤。因此实现里固定为：
     * 取旧实体 → 用请求中非 null 字段覆盖 → 用旧值补齐后整体校验 → 写库。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 并取旧实体，不存在给 A0402；</li>
     *     <li>② 用旧值补齐缺失字段，得到一份完整的待写状态；</li>
     *     <li>③ 走与新增完全相同的整体校验，保证两条入口一套规则；</li>
     *     <li>④ 检测「判定相关变更」：正确答案、题型、选项标签集合三者任一变化即算。
     *     这一步必须在覆盖旧值之前算，否则比较时永远相等；</li>
     *     <li>⑤ 只 set 白名单字段后写库。这里刻意不判断更新行数：
     *     MySQL 默认协议下「SET 的值与原值完全相同」会返回 0 行受影响，
     *     而「打开编辑框、什么都没改、点保存」是极常见的操作，当成失败会让用户莫名其妙；</li>
     *     <li>⑥ 解析 / 题源传空串表示清空：更新会跳过 null 字段，
     *     所以「改成空」必须显式写一条 UPDATE；</li>
     *     <li>⑦ 判定相关变更时重置该题所有错题条目的连对进度（不动 status）。</li>
     * </ol>
     *
     * <p>容易踩的坑：更新字段是服务端写死的白名单，绝不能把 DTO 直接转成实体整体更新——
     * 那样前端能顺手改 deletedAt 与审计字段。另一个坑是「只改选项文字」不算判定变更：
     * 标签集合没变，说明「提交时选了 C」仍指向同一个选项，重置进度反而会误伤用户。
     *
     * @param request 修改请求，不能为 null；id 必填且为正数；
     *                其余字段为 null 表示不修改，解析与题源传空串表示清空
     * @return true 表示保存成功；注意它<b>不代表数据真的变了</b>——
     *         什么都没改时也是 true，调用方不要用它判断「有没有变化」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题目不存在（A0402）、
     *         校验不通过（A0401）时抛出
     */
    boolean updateQuestion(QuizQuestionUpdateRequest request);

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * <p>为什么这样设计：题目没了，错题条目无处可去，留着只会在错题本里
     * 渲染出一条「题目已不存在」的空行。而作答记录必须保留：统计只读 result 列，
     * 不需要题目内容，删掉会让用户的累计作答数凭空减少。
     * 两次写在同一事务里，顺序是先清条目、再删题目——反过来的话，
     * 题目删除失败会留下「条目指向不存在的题」的脏数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正，并查实体确认存在，不存在给 A0402；</li>
     *     <li>② 清掉该题的全部错题条目；</li>
     *     <li>③ 逻辑删除题目（写 deleted_at，行仍在库里，可追溯）；</li>
     *     <li>④ 返回实际删除条数。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是 int 而不是 boolean，因为实现里把
     * 「删除成功 = 1、失败 = 0」显式化了；存在性已在第 ① 步校验过，
     * 因此返回 0 只可能是并发删除。另外这是逻辑删除——
     * 数据没有从库里消失，想彻底清理要走归档脚本，不要在业务代码里改成物理删除，
     * 那会让「误删可恢复」这个前提消失。
     *
     * @param id 题目 ID，必须大于 0
     * @return 实际删除的题目数（1 或 0）
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法（A0401）
     *         或题目不存在（A0402）时抛出
     */
    int deleteQuestion(long id);

    /**
     * 批量删除题目
     *
     * <p>为什么这样设计：语义是「把这些都删掉」，因此某条不存在时不中断，
     * 返回实际删除条数——批量操作里「其中一条已被别人删掉」是常态，
     * 为它让整批失败会让管理员反复重试却永远成功不了。
     * 但整个批次仍在一个事务里：要么全删、要么全不删，
     * 不会出现「删了一半，另一半因为某条异常而留在库里」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空列表，一条都没选就没什么可删的；</li>
     *     <li>② 去重：前端勾选时可能出现重复 ID（分页切换后选中状态串了），
     *     不去重会让同一条被处理两次；</li>
     *     <li>③ 逐条跳过非法 ID 与已不存在的题目，对存在的执行
     *     「清错题条目 + 逻辑删除」；</li>
     *     <li>④ 返回实际删除条数。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值是「实际删掉的条数」，不等于入参长度，也不等于去重后的长度——
     * 前端若用它显示「已删除 N 题」，需要接受 N 可能小于勾选数。
     * 另一个坑是这里逐条删除而不是一条批量 UPDATE：因为每条都要顺带清错题条目，
     * 而清理本身是按 ID 列表批量做的，两次批量操作的粒度不同，硬合成一条 SQL 反而更难读。
     *
     * @param ids 题目 ID 列表，不能为 null 或空（否则抛 A0401）；
     *            列表里可以含 null 与非正数，它们会被静默跳过
     * @return 实际删除条数，可能为 0（全部都不存在时）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 列表为空时抛 A0401
     */
    int batchDeleteQuestions(List<Long> ids);

    /**
     * 启用 / 停用题目
     *
     * <p>为什么这样设计：停用是<b>可逆的下线</b>——不再被抽到、不再出现在错题本里，
     * 但错题条目保留，重新启用后自动回来。它不清理任何数据，因此不需要事务，
     * 也不该顺手去改错题本：一旦停用就删条目，重新启用后用户的错题记录会永久消失。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正、状态落在闭集 {0, 1} 内。状态非法必须报错，
     *     静默当成启用会让管理员以为下线成功了；</li>
     *     <li>② 查实体确认存在，不存在给 A0402；</li>
     *     <li>③ 只更新 status 一列，不碰任何关联数据。</li>
     * </ol>
     *
     * <p>容易踩的坑：状态取值是 <b>0-启用、1-停用</b>，与「true 表示启用」的直觉相反，
     * 传反了会把整章题下线而界面不会有任何异常。另一个反直觉点是：
     * 已发给用户的批次里的停用题<b>仍然可以提交作答</b>——
     * 此时拒绝会让他卡在一道永远答不了的题上，停用只应影响后续抽题与错题本展示。
     *
     * @param id 题目 ID，必须大于 0
     * @param status 目标状态：0-启用，1-停用；其它值抛 A0401
     * @return true 表示更新成功；false 表示该行在更新瞬间已不存在
     *         （存在性已在前一步校验过，因此 false 只可能是并发删除）
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法 / 状态非法（A0401）、
     *         题目不存在（A0402）时抛出
     */
    boolean updateStatus(long id, int status);

    /**
     * 批量导入题目
     *
     * <p>为什么这样设计：整批全成功或全失败——任何一条解析或校验失败，整批都不落库。
     * 这是刻意的：「导入了一半」会让管理员无法判断哪些进了、哪些没进，
     * 只能全部删掉重来，而删的时候又很难精确挑出刚导入的那批。
     * 代价是一次导入的错误要一次性看完，因此返回值里带逐行错误与行号。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，并把文本交给解析器逐行解析；</li>
     *     <li>② 先收齐<b>解析阶段</b>的错误（格式不对、字段缺失），行号直接对应粘贴文本的物理行；</li>
     *     <li>③ 校验题目总数是否超过上限，超了记一条错误（行号记 0，表示不是某一行的问题）；</li>
     *     <li>④ 逐条走与新增完全相同的跨字段校验，失败的把行号与原因收进错误列表；</li>
     *     <li>⑤ 只要有任何错误就整批不落库，直接返回结果；</li>
     *     <li>⑥ dryRun 为 true 时也只校验不落库，前端据此做预览；</li>
     *     <li>⑦ 全部合法才逐条入库，题序从 startSeq 起依次 +1。</li>
     * </ol>
     *
     * <p>容易踩的坑：dryRun 与真实导入共用同一个返回结构，因此前端可以用同一套渲染逻辑
     * 做「预览」与「结果」。但要注意 dryRun 时 imported 恒为 0，
     * 判断「这批能不能导」要看 errors 是否为空，而不是看 imported。
     * 另一个坑是本方法带事务，整批 insert 在同一个事务里——
     * 一次导入几千题会长时间持有事务，不要把它做成后台任务的反面教材。
     *
     * @param request 导入请求，不能为 null；教材名、章节名、待解析文本必填；
     *                dryRun 为 null 时按 false（真导入）处理；
     *                startSeq 为 null 时按 0 处理
     * @return 导入结果，含解析成功数、实际入库数、是否 dryRun 与逐行错误列表；
     *         有错误时 imported 为 0 且整批未落库；永不为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         入库失败（B0002）时抛出。注意：单行内容不合法<b>不会</b>抛异常，
     *         而是以错误明细的形式出现在返回值里
     */
    QuizImportResultVO importQuestions(QuizQuestionImportRequest request);

    // ==================== 管理端：章节 / 教材批量操作 ====================

    /**
     * 管理端章节列表（含停用题，与 C 端口径不同）
     *
     * <p>为什么这样设计：C 端只数启用题（用户看到的是「能刷多少」），
     * 管理端要数全部题（管理员看到的是「这一章有多少存量」），
     * 因此单独给一个 VO 并额外返回「其中启用了几题」。
     * 两端共用同一个 VO 的话，必然有一端拿到的数字是错的，而这种错不会报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 一条 GROUP BY 同时取章节题数与启用题数（用 SUM(CASE WHEN) 一次算出）；</li>
     *     <li>② 逐行转 VO，教材名或章节名为 null 的脏数据行跳过；</li>
     *     <li>③ 按「教材顺序 → 教材名 → 章节顺序 → 章节名」排序，
     *     保证两次请求的顺序一致。</li>
     * </ol>
     *
     * <p>容易踩的坑：这是「扁平的章节列表」，不是嵌套的树——每一行都带教材名，
     * 前端要按教材分组得自己聚合。这样设计是因为管理端的表格是扁平的，
     * 嵌套结构反而要多一层展开逻辑。
     *
     * @return 章节列表，每行含教材名、教材顺序、章节名、章节顺序、总题数与启用题数；
     *         题库为空时返回空列表，不返回 null
     */
    List<QuizChapterAdminVO> listChaptersForAdmin();

    /**
     * 章节改名 / 调序（批量 UPDATE）
     *
     * <p>为什么这样设计：章节没有独立表，它是题目上的两个名字列，
     * 因此「改名」就是一条批量 UPDATE。逐条更新既慢，又会中途失败留下半章。
     * 改名到已存在的章节时<b>合并</b>（两章并成一章），不报错——
     * 报错会让管理员不得不先把两章改成不同名字再改回来，而他想的大概率就是合并。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，并 trim 三个名字参数；</li>
     *     <li>② 先数一下原章节是否存在，不存在给 A0402——否则更新 0 行会被误认为成功；</li>
     *     <li>③ 若新旧名字不同且目标章节已存在，记一条 info 日志说明发生了合并
     *     （合并本身不需要额外代码，UPDATE 之后自然就是一章）；</li>
     *     <li>④ 一条 UPDATE 改章节名，chapterSeq 传了才一并改。</li>
     * </ol>
     *
     * <p>容易踩的坑：新旧名字相同时不算合并，这条 UPDATE 变成「只调序」，
     * 因此「改名为自己」是合法操作而不是空操作。另外返回值是<b>受影响的题目行数</b>，
     * 不是「章节数」——一章可能有几百道题，前端不要拿它当章节计数用。
     *
     * @param request 改名请求，不能为 null；教材名、原章节名、新章节名必填；
     *                chapterSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该章题目数）；章节不存在时抛异常而不是返回 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 章节不存在时抛 A0402
     */
    int renameChapter(QuizChapterRenameRequest request);

    /**
     * 整章移到另一本教材
     *
     * <p>为什么这样设计：典型场景是导入时把章节放错了教材，或一本教材被拆成两本。
     * 目标教材下已有同名章节时合并，不报错——报错会让用户不得不先把两章改成不同名字再移。
     * 移动后教材顺序值要一并更新：章节顺序是「教材内」的概念，
     * 换了教材就该用新教材的顺序值，否则排序会错乱。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，trim 三个名字参数；</li>
     *     <li>② 校验来源章节存在，不存在给 A0402；</li>
     *     <li>③ 一条 UPDATE 改教材名，目标教材顺序值传了才一并改。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>不校验目标教材是否存在</b>。教材是题目上的名字列而不是实体，
     * 「移到一本还不存在的教材」在数据上完全合法，实际效果是新建了那本教材。
     * 这个语义容易被误读成「目标教材必须已存在」，前端应当在选择框里只给已有教材，
     * 但不要指望后端拦。另一个坑是顺序值：不传 toTextbookSeq 时会沿用原值，
     * 章节在新教材里的位置可能因此落到意料之外的地方。
     *
     * @param request 移动请求，不能为 null；来源教材名、章节名、目标教材名必填；
     *                toTextbookSeq 为 null 表示保持该章当前的教材顺序值不变
     * @return 受影响的题目行数（即该章题目数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 来源章节不存在时抛 A0402
     */
    int moveChapter(QuizChapterMoveRequest request);

    /**
     * 整章删除（题目逻辑删除 + 同事务清理错题条目）
     *
     * <p>为什么这样设计：这是本模块唯一会批量删数据的接口，因此管理端必须二次确认
     * 并在确认框里显示题数。删的是题目（逻辑删除），错题条目一并清理——
     * 题目没了，条目无处可去，留着只会在错题本里渲染出空行。
     * 作答记录保留：统计只读 result，不需要题目内容。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，trim 教材名与章节名；</li>
     *     <li>② 先查出该章全部题目 ID，为空说明章节不存在，给 A0402；</li>
     *     <li>③ 一次性清掉这批题目的错题条目（按 ID 列表批量删，不是逐条）；</li>
     *     <li>④ 逐条逻辑删除题目并计数，最后记一条 info 日志（删了多少题要留痕）。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是「实际删掉的题目数」，章节不存在时抛异常而不是返回 0，
     * 这样前端能区分「删了 0 题」与「这章根本不存在」。
     * 另一个坑是它按「教材名 + 章节名」定位，而不是按章节 ID——
     * 教材章节没有 ID，若两个章节被改名合并成一个，这里会把合并后的整章一起删掉。
     *
     * @param request 删除请求，不能为 null；教材名与章节名必填
     * @return 实际删除的题目数；永不为 0（章节不存在会抛异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 章节不存在时抛 A0402
     */
    int deleteChapter(QuizChapterDeleteRequest request);

    /**
     * 教材改名 / 调序（批量 UPDATE）
     *
     * <p>为什么这样设计：教材同样没有独立表，它就是题目上的 textbook_name +
     * textbook_seq 两列，因此「改名」= 一条批量 UPDATE，改到已存在的名字时自然合并。
     * 与章节改名是同一套做法，区别只在不带章节维度——教材改名会把它下面所有章节
     * 一起带走。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，trim 新旧名字；</li>
     *     <li>② 先数一下原教材是否存在，不存在给 A0402；</li>
     *     <li>③ 一条 UPDATE 改教材名，textbookSeq 传了才一并改。</li>
     * </ol>
     *
     * <p>容易踩的坑：这是全库范围的批量更新，改错一个名字会影响整本教材的所有题目，
     * 而接口不会提示「影响了多少章节」。前端确认框里应当先展示「该教材下有 N 章 M 题」。
     * 另外改名不会改动章节名，因此「改名后的教材里还是原来的章节」是正常现象。
     *
     * @param request 改名请求，不能为 null；原教材名与新教材名必填；
     *                textbookSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该教材的题目总数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 教材不存在时抛 A0402
     */
    int renameTextbook(QuizTextbookRenameRequest request);
}

package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.quiz.QuizAnswerRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizBatchRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizSkipRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizWrongQueryRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizWrongResolveRequest;
import com.bhu.runshistudioweb.model.vo.quiz.QuizBatchVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOverviewVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizTextbookVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizVerdictVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongDetailVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongItemVO;
import com.bhu.runshistudioweb.service.QuizPracticeService;
import com.bhu.runshistudioweb.service.QuizQuestionService;
import com.bhu.runshistudioweb.service.QuizWrongService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 刷题接口（C 端，仅工作室成员）
 *
 * 准入规则：全部方法只挂 {@code @SaCheckLogin}，真正的成员校验在 Service 层，
 * 查的是 studio_member 表里的在队档案。
 *
 * 为什么这里只挂 {@code @SaCheckLogin} 而不挂 {@code @SaCheckRole("member")}：
 * 与 AttendanceController 完全同一条理由——「成员」不是角色标签，
 * 而是管理员在成员表里录的一条档案。角色可能与档案不同步，
 * 若按角色放行，就绕开了真正的准入规则。
 *
 * 白名单一条都不加：/quiz/** 全部走「默认要求登录」。
 * 刷题是成员专属能力，没有任何匿名入口——这与「游客可浏览帖子」的定位不同。
 *
 * 答案不下发的边界（本模块的安全底线）：
 * - /quiz/batch（出题）返回的题目 VO 结构上没有答案字段；
 * - /quiz/answer（提交）只返回刚提交那一题的答案与解析；
 * - /quiz/skip（跳过）什么都不返回——否则跳过就成了看答案的捷径；
 * - /quiz/wrong/page（错题列表）不含答案；详情 /quiz/wrong/detail 才含，
 * 前提是这道题该用户已经做错过。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/quiz/catalog。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/quiz")
@Tag(name = "刷题模块", description = "工作室成员刷政治选择题：三种模式、服务端判分、错题本")
@RequiredArgsConstructor
public class QuizController {

    private final QuizQuestionService quizQuestionService;

    private final QuizPracticeService quizPracticeService;

    private final QuizWrongService quizWrongService;

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * <p>为什么这样设计：本方法要显式校验成员身份，因为数据来自 QuizQuestionService，
     * 那个 Service 是管理端与 C 端共用的，它不做准入判定——管理端的调用方
     * 已经由角色注解挡过了，所以它没必要、也不应该再查一次成员表。
     * 若这里只挂登录注解，「只有账号、没有成员档案」的普通用户就能读到题库目录。
     * 数据本身不敏感，但这是准入规则的漏洞：他本不该进入这个模块的任何入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先做成员校验并丢弃返回值。顺序不能换——非成员连一个空列表都不该拿到，
     *     否则「有没有权限」这件事就变成了「能不能看懂返回结构」；</li>
     *     <li>② 再调 Service 取教材与章节树；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个接口是全模块唯一「Controller 里出现业务校验」的地方，
     * 看起来破坏了「Controller 只做三件事」的约定。这是刻意的取舍：
     * 与其在 Controller 里复制一遍「查成员表」的逻辑（两份规则迟早漂移），
     * 不如把判定暴露成 Service 的公开方法，让所有 /quiz/** 入口共用同一个实现。
     *
     * @return 教材列表，每本含其章节（只统计启用题；章节随题目存在，空章节不会出现）；
     * 题库为空时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 不是在队成员（A0301）时抛出
     */
    @GetMapping("/catalog")
    @SaCheckLogin
    @Operation(summary = "【成员】教材与章节树", description = "只统计启用题；章节随题目存在，空章节不会出现")
    public BaseResponse<List<QuizTextbookVO>> listCatalog() {
        // ① 成员校验放在取数据前：非成员连空列表都不该拿到
        quizPracticeService.requireActiveMemberId();
        // ②③ 数据源来自题库服务，它不重复做准入判定（管理端也在用同一个方法）
        return ResultUtils.success(quizQuestionService.listCatalog());
    }

    /**
     * 学习概览（四个数字）
     *
     * <p>为什么这样设计：四个指标（累计作答、正确率、待复习错题、已消解）
     * 由两次聚合查询算出来，不是四次查询——「四个指标四次查询」是这类统计接口
     * 最常见的浪费，而且两次查询之间若有新作答，数字还会自相矛盾。
     * 正确率在 0 次作答时返回 null 而不是 0：0% 会被误读成「全错」，
     * 而 null 表示「还没开始」，前端据此显示「--」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参，用户身份由登录态决定；</li>
     *     <li>② 调 Service：先做成员校验，再两次聚合查询算出四个数字；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：概览里的「已消解」是当前状态，不是历史累计——
     * 再答错会回到待复习，这个数字就该减 1。若把它做成只增不减的计数器，
     * 用户看到的「已掌握 20 题」会与错题本里的实际条目对不上。
     *
     * @return 学习概览：累计作答数、正确率（可为 null）、待复习错题数、已消解数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 不是在队成员（A0301）时抛出
     */
    @GetMapping("/overview")
    @SaCheckLogin
    @Operation(summary = "【成员】学习概览", description = "累计作答与正确率不含跳过；正确率在 0 次作答时为 null")
    public BaseResponse<QuizOverviewVO> getOverview() {
        // ① 无入参，用户身份由登录态决定
        // ②③ 成员校验与聚合统计都在 Service 里，两次查询算完四个数字
        return ResultUtils.success(quizPracticeService.getOverview());
    }

    /**
     * 开一批题目
     *
     * <p>为什么这样设计：批次 ID 由服务端生成后返回，请求体里没有它。
     * 让前端传批次 ID 等于把「本轮是哪几题」的解释权交出去——
     * 前端可以复用别人的批次号，或者伪造一个从未存在的批次。
     * 三种模式的必填参数不同：顺序模式必须指定教材与章节，错题重刷与随机刷题
     * 可只指定范围（或完全不指定）。这种「按模式决定必填性」的规则没法用注解表达，
     * 只能由 Service 校验，且提示要写清是哪个模式缺了哪个参数。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（mode 必填），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 交给 Service：成员校验 → 模式取值校验（闭集，不默认成某个模式）→
     *     抽题 → 组装批次（题目不含答案）；</li>
     *     <li>③ 返回批次 ID 与题目列表。批次不落库——题目本身不含答案，
     *     整批交给前端持有即可，服务端只需要批次 ID 做幂等。</li>
     * </ol>
     *
     * <p>容易踩的坑：模式取值非法时不能「默认成顺序模式」。那样用户以为在刷错题，
     * 实际在刷顺序题，而界面上的标题、进度、判定全都会跟着错，且不会有任何报错。
     *
     * @param request 开批请求：mode 必填（0 顺序 / 1 错题重刷 / 2 随机）；
     *                顺序模式下教材与章节也必填
     * @return 批次 ID + 题目列表（结构上不含答案）；范围内无题时由 Service 抛异常而不是返回空批次
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或非成员（A0201 / A0301）、
     * 模式非法、顺序模式缺教材或章节、章节已刷完、错题本为空、范围内无题（均 A0401）时抛出
     */
    @PostMapping("/batch")
    @SaCheckLogin
    @Operation(summary = "【成员】开始一轮刷题",
            description = "mode：0-顺序（须选教材+章节）, 1-错题重刷, 2-随机；返回的题目不含答案")
    public BaseResponse<QuizBatchVO> startBatch(@RequestBody @Valid QuizBatchRequest request) {
        // ① @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「哪个模式缺哪个参数」由 Service 判断：注解表达不了按模式变化的必填性
        return ResultUtils.success(quizPracticeService.startBatch(request));
    }

    /**
     * 提交作答（幂等）
     *
     * <p>为什么这样设计：判分必须在服务端。题目 VO 结构上没有答案字段，
     * 正确答案只在本次返回值里出现——这是本模块的安全底线。
     * 重复提交同一题（断网重试、双击）会返回首次判定，不重复计数、
     * 不重复影响错题本：幂等（重复调用结果一致）由「用户 + 批次 + 题目」上的唯一键保证，
     * 撞键时重读已有记录、原样返回首次结论。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：成员校验 → 题目存在且启用 → 标签归一化并判分 →
     *     落一条作答记录 → 按判定结果更新错题本；</li>
     *     <li>③ 返回判定结果（含正确答案与解析）。前端拿到后才能高亮正确选项。</li>
     * </ol>
     *
     * <p>容易踩的坑：多选不给部分分。全对才算对，少选一个也是错——
     * 这不是严格，而是与错题本的口径保持一致：错题本记录的是「这道题你还没掌握」，
     * 若部分分算对，用户会看到「答对了但进了错题本」这种自相矛盾的结果。
     * 另外单选传了两个以上标签属于非法请求（正常界面产生不了），直接 A0401。
     *
     * @param request 作答请求：batchId、questionId、chosenLabels 均必填；
     *                chosenLabels 形如 "AC"，大小写与分隔符会被归一化
     * @return 判定结果：是否正确、正确答案、解析、错题本动作
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或非成员（A0201 / A0301）、
     * 题目不存在或已停用（A0402）、标签非法或单选传了多个（A0401）时抛出
     */
    @PostMapping("/answer")
    @SaCheckLogin
    @Operation(summary = "【成员】提交答案",
            description = "服务端判分；多选不给部分分；重复提交返回首次判定（幂等）")
    public BaseResponse<QuizVerdictVO> submitAnswer(@RequestBody @Valid QuizAnswerRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 幂等靠唯一键实现：撞键时返回首次判定，而不是报「本题已作答」
        return ResultUtils.success(quizPracticeService.submitAnswer(request));
    }

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * <p>为什么这样设计：返回布尔值而不是「进度对象」。一旦返回任何题目相关信息，
     * 跳过按钮就可能被用来探测答案——反复跳过直到看见自己想要的字段。
     * 前端自己维护「第几题」，服务端不需要告诉它下一题是什么。
     * 服务端仍会落一条「跳过」记录：否则断点续答无法区分「处理过」与「还没轮到」，
     * 幂等也没法做。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：成员校验 → 题目存在 → 落一条跳过记录（不写错题本）；</li>
     *     <li>③ 只返回 true。没有答案、没有解析、没有下一题——这是刻意的信息最小化。</li>
     * </ol>
     *
     * <p>容易踩的坑：跳过不记错，但下一轮顺序刷题仍会遇到它。跳过只影响「本轮进度」，
     * 不影响掌握度——若把跳过当成「已处理」而从后续轮次里排除，
     * 用户就会靠一路跳过把整章刷成「已完成」，而实际上什么都没学会。
     *
     * @param request 跳过请求：批次与题目标识必填
     * @return true 表示记录成功（失败会抛异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或非成员（A0201 / A0301）、
     * 题目不存在（A0402）、参数不合法（A0401）时抛出
     */
    @PostMapping("/skip")
    @SaCheckLogin
    @Operation(summary = "【成员】跳过本题",
            description = "跳过不记错、不返回答案；下一轮顺序刷题仍会遇到它")
    public BaseResponse<Boolean> skipQuestion(@RequestBody @Valid QuizSkipRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 只回 true：任何额外的返回信息都可能被用来探测答案
        return ResultUtils.success(quizPracticeService.skipQuestion(request));
    }

    /**
     * 我的错题本（分页，不含答案）
     *
     * <p>为什么这样设计：列表不含答案，只有详情才含。浏览列表不该泄露答案——
     * 用户只要反复翻列表就能看到所有错题的正确答案，错题本也就失去意义了。
     * 只返回待复习的条目：已消解的条目留在库里是为了保留「什么时候掌握的」，
     * 但不再展示。同时过滤掉已停用、已被删除的题，且与计数用同一个条件，
     * 否则会出现「计数 9 条、列表只有 7 条」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 QuizWrongQueryRequest，允许一个参数都不传；</li>
     *     <li>② 用私有方法取当前登录人 ID，再连同查询条件交给 Service；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：用户 ID 必须从登录态取，不能由前端传。若加了 userId 参数，
     * 「翻别人的错题本」就成了一个功能——而错题本能反推出一个人学过什么、错在哪。
     * 另外分页参数非法时 Service 会兜底纠正，这里不加 {@code @Valid} 是刻意的：
     * 页码传错不该让整个查询失败。
     *
     * @param request 查询条件：教材与章节精确筛选（选填）、分页；允许整体为 null
     * @return 分页结果，记录为 QuizWrongItemVO（不含答案）；没有待复习条目时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）时抛出
     */
    @GetMapping("/wrong/page")
    @SaCheckLogin
    @Operation(summary = "【成员】我的错题本",
            description = "只含待复习；列表不含答案，详情才含；已停用的题不显示")
    public BaseResponse<Page<QuizWrongItemVO>> listMyWrong(QuizWrongQueryRequest request) {
        // ① 查询条件由 Spring 从查询串绑定，一个参数都不传也合法
        // ②③ 用户 ID 来自登录态；错题服务本身不做成员校验，因此这里只保证「是谁」
        return ResultUtils.success(quizWrongService.listMyWrongByPage(currentUserId(), request));
    }

    /**
     * 错题详情（含答案与解析）
     *
     * <p>为什么这样设计：能进错题本的前提是「这道题你做错过」，做错过的人看到答案是应该的。
     * 但归属必须校验：不是本人的条目按「不存在或无权访问」处理（A0301），
     * 而不是「这条记录是别人的」——后者等于确认了这条记录存在。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（错题本条目的 ID，不是题目 ID）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何条目，直接给 A0401，
     *     省掉一次数据库往返；</li>
     *     <li>③ 调 Service：查条目 → 校验归属 → 组装详情（含正确答案、解析、
     *     以及你上次选了什么）。</li>
     * </ol>
     *
     * <p>容易踩的坑：参数是「错题本条目 ID」而不是「题目 ID」。两者都是 19 位雪花 ID，
     * 传错时不会报类型错误——它只会返回「不存在」，排查起来很费劲。
     * 前端必须用列表接口返回的条目 id，而不是题目 id。
     *
     * @param id 错题本条目 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 详情（含正确答案与解析，以及上次所选标签）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 条目不存在或不属于当前用户（A0301）、id 不合法（A0401）时抛出
     */
    @GetMapping("/wrong/detail")
    @SaCheckLogin
    @Operation(summary = "【成员】错题详情", description = "含正确答案与解析，以及你上次选了什么")
    public BaseResponse<QuizWrongDetailVO> getMyWrongDetail(@RequestParam("id") long id) {
        // ① 用 long 接必填的错题本条目 ID（不是题目 ID）：它们不可能对应任何条目，查库只会返回一个误导性的「不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        // ③ 归属校验在 Service 里：别人的条目与不存在的条目返回同一个错误，不暴露存在性
        return ResultUtils.success(quizWrongService.getMyWrongDetail(currentUserId(), id));
    }

    /**
     * 手动标记「已掌握」
     *
     * <p>为什么这样设计：自动消解靠连续答对若干次，但有些人就是不想再刷那道题了——
     * 手动标记给他们一个出口。它是幂等的：已消解时直接返回 true，
     * 且不修改消解时间——重复点击不该把「什么时候掌握的」这个时间点改掉。
     * 归属校验与详情接口同一套，不是本人的条目按「不存在或无权访问」处理。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，请求体与 id 一起判——空 body 时 {@code @Valid} 不会生效；</li>
     *     <li>② 用私有方法取当前登录人 ID；</li>
     *     <li>③ 调 Service：查条目 → 校验归属 → 写消解状态（已消解时直接返回）。</li>
     * </ol>
     *
     * <p>容易踩的坑：手动消解之后，如果该用户又答错了同一道题，条目会重新回到待复习。
     * 这是对的——「标记已掌握」表达的是当下的判断，不是永久豁免。
     * 若把它做成一次性开关，错题本就会慢慢积累一批「明明还在错、却已经消失」的题。
     *
     * @param request 请求：错题本条目 id 必填且为正整数
     * @return true 表示成功（含「本来就已经消解」的情况）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 条目不存在或不属于当前用户（A0301）、id 不合法（A0401）时抛出
     */
    @PostMapping("/wrong/resolve")
    @SaCheckLogin
    @Operation(summary = "【成员】标记已掌握", description = "幂等：已消解时直接返回，不改消解时间")
    public BaseResponse<Boolean> resolveWrong(@RequestBody @Valid QuizWrongResolveRequest request) {
        // ① 请求体与 id 都要判：空 body 时取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ②③ 幂等与「不改消解时间」都在 Service 里，Controller 只负责转发
        return ResultUtils.success(quizWrongService.resolveManually(currentUserId(), request.getId()));
    }

    /**
     * 当前登录用户 ID
     *
     * <p>为什么这样设计：抽一个私有方法，而不是在每个接口里各写一遍取值。
     * 三个错题接口都要传「是谁」，而成员校验发生在 Service 层，
     * Controller 这里只需要「是谁」。抽出来还能保证三处用的是同一套取值方式——
     * 若某处写成 getLoginIdAsString() 再自己转型，迟早出现一处漏转型的 bug。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接从 Sa-Token 的登录态里取当前账号 ID。未登录的情况走不到这里：
     *     三个调用方都挂了 {@code @SaCheckLogin}，拦截器会先抛 A0201；</li>
     *     <li>② 返回 long。注意这是「账号 ID」，不是成员档案 ID——
     *     错题本按账号维度存，成员档案只是准入判据。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不判登录。它看起来是个危险的空子，实际上调用链上
     * 每个入口都有登录注解；但若将来有人把它用在没挂注解的方法里，
     * 未登录时会抛 NotLoginException，最终仍是 A0201，不会取到一个假的 0。
     *
     * @return 当前登录账号 ID（雪花算法生成的 19 位长整型，以 long 返回）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时由全局异常处理器转成 A0201；
     * 本方法自身不捕获，异常会直接冒泡到调用方
     */
    private long currentUserId() {
        return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
    }
}

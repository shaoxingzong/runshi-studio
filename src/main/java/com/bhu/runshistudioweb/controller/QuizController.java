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
 * 准入规则：全部方法只挂 @SaCheckLogin，真正的成员校验在 Service 层
 * （ QuizPracticeServiceImpl#requireActiveMember），查的是 studio_member 表。
 *
 * 为什么这里只挂 @SaCheckLogin 而不挂 @SaCheckRole("member")：
 * 与 AttendanceController 完全同一条理由——「成员」不是角色标签，
 * 而是管理员在成员表里录的一条档案。角色可能与档案不同步，
 * 若按角色放行，就绕开了真正的准入规则。
 *
 * 白名单一条都不加：/quiz/** 全部走「默认要求登录」。
 * 刷题是成员专属能力，没有任何匿名入口——这与「游客可浏览帖子」的定位不同。
 *
 * 答案不下发的边界：
 * - /quiz/batch（出题）返回 QuizQuestionVO，结构上没有答案字段；
 * - /quiz/answer（提交）返回 QuizVerdictVO，只含刚提交那一题的答案；
 * - /quiz/skip（跳过）什么都不返回——否则跳过就成了看答案的捷径；
 * - /quiz/wrong/page（错题列表）不含答案；详情 /quiz/wrong/detail 才含，
 * 前提是这道题该用户已经做错过。
 *
 * 接口地址前缀 /api，完整路径形如 http://localhost:8080/api/quiz/catalog。
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
     * 为什么这里要显式校验成员身份：数据来自 QuizQuestionService，
     * 那个 Service 是管理端与 C 端共用的，不做准入判定。若这里只挂
     * @SaCheckLogin，「只有账号、没有成员档案」的普通用户就能读到题库目录——
     * 数据不敏感，但这是准入规则的漏洞：他本不该进入这个模块的任何入口。
     *
     * @return 教材列表，每本含其章节（只统计启用题）
     */
    @GetMapping("/catalog")
    @SaCheckLogin
    @Operation(summary = "【成员】教材与章节树", description = "只统计启用题；章节随题目存在，空章节不会出现")
    public BaseResponse<List<QuizTextbookVO>> listCatalog() {
        // 成员校验放在取数据之前：非成员连空列表都不该拿到
        quizPracticeService.requireActiveMemberId();
        return ResultUtils.success(quizQuestionService.listCatalog());
    }

    /**
     * 学习概览（四个数字）
     *
     * @return 累计作答 / 正确率 / 待复习错题 / 已消解
     */
    @GetMapping("/overview")
    @SaCheckLogin
    @Operation(summary = "【成员】学习概览", description = "累计作答与正确率不含跳过；正确率在 0 次作答时为 null")
    public BaseResponse<QuizOverviewVO> getOverview() {
        return ResultUtils.success(quizPracticeService.getOverview());
    }

    /**
     * 开一批题目
     *
     * 三种模式的必填参数不同：顺序模式必须指定教材 + 章节；
     * 错题重刷与随机刷题可只指定范围（或完全不指定 = 全库）。
     *
     * @param request 开批请求（mode 必填）
     * @return 批次 ID + 题目列表（不含答案）
     */
    @PostMapping("/batch")
    @SaCheckLogin
    @Operation(summary = "【成员】开始一轮刷题",
            description = "mode：0-顺序（须选教材+章节）, 1-错题重刷, 2-随机；返回的题目不含答案")
    /**
     * 开一批题（题目不含答案）
     *
     * @param request 开批请求
     * @return 批次（含题目列表）
     */
    public BaseResponse<QuizBatchVO> startBatch(@RequestBody @Valid QuizBatchRequest request) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizPracticeService.startBatch(request));
    }

    /**
     * 提交作答（幂等）
     *
     * 重复提交同一题（断网重试、双击）会返回首次判定，
     * 不重复计数、不重复影响错题本。
     *
     * @param request 作答请求（batchId / questionId / chosenLabels 必填）
     * @return 判定结果 + 正确答案 + 解析 + 错题本动作
     */
    @PostMapping("/answer")
    @SaCheckLogin
    @Operation(summary = "【成员】提交答案",
            description = "服务端判分；多选不给部分分；重复提交返回首次判定（幂等）")
    /**
     * 提交作答，由服务端判分
     *
     * @param request 作答请求
     * @return 判定结果（含正确答案与解析）
     */
    public BaseResponse<QuizVerdictVO> submitAnswer(@RequestBody @Valid QuizAnswerRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizPracticeService.submitAnswer(request));
    }

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * 返回布尔而不是「进度对象」：一旦返回任何题目相关信息，
     * 跳过按钮就可能被用来探测答案。前端自己维护「第几题」。
     *
     * @param request 跳过请求
     * @return true 表示记录成功
     */
    @PostMapping("/skip")
    @SaCheckLogin
    @Operation(summary = "【成员】跳过本题",
            description = "跳过不记错、不返回答案；下一轮顺序刷题仍会遇到它")
    /**
     * 跳过当前题
     *
     * @param request 跳过请求
     * @return 是否成功
     */
    public BaseResponse<Boolean> skipQuestion(@RequestBody @Valid QuizSkipRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizPracticeService.skipQuestion(request));
    }

    /**
     * 我的错题本（分页，不含答案）
     *
     * @param request 查询条件（可按教材/章节筛选）
     * @return 分页结果
     */
    @GetMapping("/wrong/page")
    @SaCheckLogin
    @Operation(summary = "【成员】我的错题本",
            description = "只含待复习；列表不含答案，详情才含；已停用的题不显示")
    /**
     * 我的错题本（待复习）
     *
     * @param request 查询条件
     * @return 分页结果
     */
    public BaseResponse<Page<QuizWrongItemVO>> listMyWrong(QuizWrongQueryRequest request) {
        return ResultUtils.success(quizWrongService.listMyWrongByPage(currentUserId(), request));
    }

    /**
     * 错题详情（含答案与解析）
     *
     * @param id 错题本条目 ID（不是题目 ID）
     * @return 详情
     */
    @GetMapping("/wrong/detail")
    @SaCheckLogin
    @Operation(summary = "【成员】错题详情", description = "含正确答案与解析，以及你上次选了什么")
    public BaseResponse<QuizWrongDetailVO> getMyWrongDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        return ResultUtils.success(quizWrongService.getMyWrongDetail(currentUserId(), id));
    }

    /**
     * 手动标记「已掌握」
     *
     * @param request 请求（错题本条目 ID）
     * @return true 表示成功（已消解时幂等返回 true）
     */
    @PostMapping("/wrong/resolve")
    @SaCheckLogin
    @Operation(summary = "【成员】标记已掌握", description = "幂等：已消解时直接返回，不改消解时间")
    public BaseResponse<Boolean> resolveWrong(@RequestBody @Valid QuizWrongResolveRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizWrongService.resolveManually(currentUserId(), request.getId()));
    }

    /**
     * 当前登录用户 ID
     *
     * 抽一个私有方法而不是在每个接口里写 StpUtil.getLoginIdAsLong()：
     * 成员校验发生在 Service 层，Controller 这里只需要「是谁」。
     * 未登录由拦截器直接挡掉（A0201），走不到这里。
     */
    private long currentUserId() {
        return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
    }
}

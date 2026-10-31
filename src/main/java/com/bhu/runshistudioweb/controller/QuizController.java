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
     * @return 教材列表，每本含章节（只统计启用题）；题库为空时返回空列表
     */
    @GetMapping("/catalog")
    @SaCheckLogin
    @Operation(summary = "【成员】教材与章节树", description = "只统计启用题；章节随题目存在，空章节不会出现")
    public BaseResponse<List<QuizTextbookVO>> listCatalog() {
        // 成员校验放在取数据前：非成员连空列表都不该拿到
        quizPracticeService.requireActiveMemberId();
        // ③ 数据源来自题库服务，它不重复做准入判定（管理端也在用同一个方法）
        return ResultUtils.success(quizQuestionService.listCatalog());
    }

    /**
     * 学习概览（四个数字）
     *
     * @return 累计作答数、正确率（0 次作答时为 null，前端显示「--」）、待复习错题数、已消解数
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
     * @param request mode 必填（0 顺序 / 1 错题重刷 / 2 随机）；顺序模式下教材与章节也必填
     * @return 批次 ID 与题目列表（结构上不含答案）
     */
    @PostMapping("/batch")
    @SaCheckLogin
    @Operation(summary = "【成员】开始一轮刷题",
            description = "mode：0-顺序（须选教材+章节）, 1-错题重刷, 2-随机；返回的题目不含答案")
    public BaseResponse<QuizBatchVO> startBatch(@RequestBody @Valid QuizBatchRequest request) {
        // @Valid 只校验字段级约束，请求体整体为 null 时不会触发，兜一层防 NPE
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 「哪个模式缺哪个参数」由 Service 判断：注解表达不了按模式变化的必填性
        return ResultUtils.success(quizPracticeService.startBatch(request));
    }

    /**
     * 提交作答（幂等）
     *
     * @param request batchId、questionId、chosenLabels 均必填，标签形如 "AC"，会被归一化
     * @return 判定结果：是否正确、正确答案、解析、错题本动作
     */
    @PostMapping("/answer")
    @SaCheckLogin
    @Operation(summary = "【成员】提交答案",
            description = "服务端判分；多选不给部分分；重复提交返回首次判定（幂等）")
    public BaseResponse<QuizVerdictVO> submitAnswer(@RequestBody @Valid QuizAnswerRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 幂等靠唯一键实现：撞键时返回首次判定，而不是报「本题已作答」
        return ResultUtils.success(quizPracticeService.submitAnswer(request));
    }

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * @param request 批次与题目标识必填
     * @return true 表示记录成功，失败抛异常
     */
    @PostMapping("/skip")
    @SaCheckLogin
    @Operation(summary = "【成员】跳过本题",
            description = "跳过不记错、不返回答案；下一轮顺序刷题仍会遇到它")
    public BaseResponse<Boolean> skipQuestion(@RequestBody @Valid QuizSkipRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 只回 true：任何额外的返回信息都可能被用来探测答案
        return ResultUtils.success(quizPracticeService.skipQuestion(request));
    }

    /**
     * 我的错题本（分页，不含答案）
     *
     * @param request 教材与章节精确筛选（选填）+ 分页；允许整体为 null
     * @return 分页结果（QuizWrongItemVO，不含答案）
     */
    @GetMapping("/wrong/page")
    @SaCheckLogin
    @Operation(summary = "【成员】我的错题本",
            description = "只含待复习；列表不含答案，详情才含；已停用的题不显示")
    public BaseResponse<Page<QuizWrongItemVO>> listMyWrong(QuizWrongQueryRequest request) {
        return ResultUtils.success(quizWrongService.listMyWrongByPage(currentUserId(), request));
    }

    /**
     * 错题详情（含答案与解析）
     *
     * @param id 错题本条目 ID（不是题目 ID），必须大于 0
     * @return 详情（含正确答案、解析与上次所选标签）
     */
    @GetMapping("/wrong/detail")
    @SaCheckLogin
    @Operation(summary = "【成员】错题详情", description = "含正确答案与解析，以及你上次选了什么")
    public BaseResponse<QuizWrongDetailVO> getMyWrongDetail(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        // 归属校验在 Service 里：别人的条目与不存在的条目返回同一个错误，不暴露存在性
        return ResultUtils.success(quizWrongService.getMyWrongDetail(currentUserId(), id));
    }

    /**
     * 手动标记「已掌握」
     *
     * @param request 错题本条目 id 必填且为正整数
     * @return true 表示成功（含「本来就已经消解」）
     */
    @PostMapping("/wrong/resolve")
    @SaCheckLogin
    @Operation(summary = "【成员】标记已掌握", description = "幂等：已消解时直接返回，不改消解时间")
    public BaseResponse<Boolean> resolveWrong(@RequestBody @Valid QuizWrongResolveRequest request) {
        // 请求体与 id 都要判：空 body 时取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ③ 幂等与「不改消解时间」都在 Service 里，Controller 只负责转发
        return ResultUtils.success(quizWrongService.resolveManually(currentUserId(), request.getId()));
    }

    /** 当前登录账号 ID */
    private long currentUserId() {
        return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
    }
}

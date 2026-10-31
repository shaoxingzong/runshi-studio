package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterDeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterMoveRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterRenameRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionAddRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionBatchDeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionImportRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionQueryRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionStatusRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionUpdateRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizTextbookRenameRequest;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizImportResultVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionAdminVO;
import com.bhu.runshistudioweb.service.QuizQuestionService;
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
 * 刷题题库管理接口（管理端，全部要求 admin）
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/quiz")
@Tag(name = "题库管理", description = "管理员维护刷题题目、章节与教材；支持批量导入")
@RequiredArgsConstructor
public class AdminQuizController {

    private final QuizQuestionService quizQuestionService;

    // 题目

    /**
     * 题目分页（含选项与答案、作答数与正确率）
     *
     * @param request 教材、章节、题型、状态精确，题干模糊 + 分页；允许整体为 null
     * @return 分页结果（QuizQuestionAdminVO，含选项、正确答案与作答统计）
     */
    @GetMapping("/question/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目分页",
            description = "支持教材/章节/题型/关键词/状态筛选；附作答数与正确率")
    public BaseResponse<Page<QuizQuestionAdminVO>> listQuestion(@Valid QuizQuestionQueryRequest request) {
        return ResultUtils.success(quizQuestionService.listQuestionByPage(request));
    }

    /**
     * 题目详情（含选项与答案）
     *
     * @param id 题目 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return QuizQuestionAdminVO（含选项与正确答案）
     */
    @GetMapping("/question/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目详情", description = "含选项与正确答案，供编辑回填")
    public BaseResponse<QuizQuestionAdminVO> getQuestion(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // 管理端详情含答案，因此不能把这个 VO 复用到任何 C 端接口上
        return ResultUtils.success(quizQuestionService.getQuestionById(id));
    }

    /**
     * 新增题目
     *
     * @param request 题干、题型、选项列表必填；标签须从 A 连续，跳号会让判分无法排查
     * @return 新题目 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/question/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增题目",
            description = "单选恰好 1 个正确项；多选至少 2 个；选项标签须从 A 连续")
    public BaseResponse<Long> addQuestion(@RequestBody @Valid QuizQuestionAddRequest request) {
        // 空 body 时 @Valid 整条链路都不生效，必须先挡
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 跨字段规则（正确项个数、标签连续性）只能由 Service 整体校验
        return ResultUtils.success(quizQuestionService.addQuestion(request));
    }

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * @param request id 必填，其余字段为 null 表示保持原值
     */
    @PostMapping("/question/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】修改题目",
            description = "null 表示不修改；改答案/题型/选项标签会重置错题连对进度（不改变已消解状态）")
    public BaseResponse<Boolean> updateQuestion(@RequestBody @Valid QuizQuestionUpdateRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 重置错题进度这件事在 Service 的同一事务里完成，Controller 不感知
        return ResultUtils.success(quizQuestionService.updateQuestion(request));
    }

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * @param request id 必填且为正整数
     * @return 实际删除条数（正常为 1），与批量删除保持同一返回形态
     */
    @PostMapping("/question/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除题目",
            description = "逻辑删除；同事务清理错题条目；作答记录保留。只想临时下线请用「停用」")
    public BaseResponse<Integer> deleteQuestion(@RequestBody @Valid DeleteRequest request) {
        // 请求体与 id 都要判：空 body 时取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ③ 返回的是「删了几条」而不是布尔值，与批量删除接口保持同一套返回值形态
        return ResultUtils.success(quizQuestionService.deleteQuestion(request.getId()));
    }

    /**
     * 批量删除题目
     *
     * @param request ids 必填且非 null，至少一条（空列表由 Service 拦成 A0401）
     * @return 实际删除条数，可能小于 ids 的个数
     */
    @PostMapping("/question/batch-delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量删除题目", description = "整批同一事务；某条不存在不中断")
    public BaseResponse<Integer> batchDeleteQuestions(
            @RequestBody @Valid QuizQuestionBatchDeleteRequest request) {
        // ids 为 null 时遍历会 NPE，因此这里必须与请求体一起判
        ThrowUtils.throwIf(request == null || request.getIds() == null, ErrorCode.PARAMS_ERROR);
        // ③ 整批同一事务，返回条数可能小于传入个数（部分已被删除）
        return ResultUtils.success(quizQuestionService.batchDeleteQuestions(request.getIds()));
    }

    /**
     * 启用 / 停用题目
     *
     * @param request id 必填，status 取 0（启用）或 1（停用）
     */
    @PostMapping("/question/status")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】启用/停用题目",
            description = "停用可逆：错题条目保留，重新启用后自动回到错题本")
    public BaseResponse<Boolean> updateQuestionStatus(
            @RequestBody @Valid QuizQuestionStatusRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 只把 id 与 status 交给 Service：方法签名即契约，DTO 的其余字段与它无关
        return ResultUtils.success(
                quizQuestionService.updateStatus(request.getId(), request.getStatus()));
    }

    /**
     * 批量导入题目
     *
     * @param request 教材名、章节名、待解析文本必填；dryRun 为 true 时只校验不落库
     * @return 解析与导入结果，含成功条数与逐行错误
     */
    @PostMapping("/question/import")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量导入题目",
            description = "粘贴文本；支持【单选】/【多选】+ 行尾 * 或「答案：ABD」；整批全成功或全失败")
    public BaseResponse<QuizImportResultVO> importQuestions(
            @RequestBody @Valid QuizQuestionImportRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 整批原子性由 Service 保证，Controller 不在这里做「逐条重试」之类的补偿
        return ResultUtils.success(quizQuestionService.importQuestions(request));
    }

    // 章节 / 教材

    /**
     * 章节列表（管理端口径：含停用题）
     *
     * @return 章节列表（QuizChapterAdminVO，含题目总数与启用数）
     */
    @GetMapping("/chapter/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节列表",
            description = "含停用题；同时给出启用数，便于判断「共 50 题（启用 48）」")
    public BaseResponse<List<QuizChapterAdminVO>> listChapters() {
        return ResultUtils.success(quizQuestionService.listChaptersForAdmin());
    }

    /**
     * 章节改名 / 调序
     *
     * @param request 教材名、原章节名、新章节名必填；chapterSeq 为 null 表示不改顺序
     * @return 受影响的题目行数
     */
    @PostMapping("/chapter/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节改名/调序",
            description = "批量 UPDATE；改名到已存在的章节时自动合并")
    public BaseResponse<Integer> renameChapter(@RequestBody @Valid QuizChapterRenameRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 一条批量 UPDATE 在事务里原子完成，返回受影响行数供前端展示影响面
        return ResultUtils.success(quizQuestionService.renameChapter(request));
    }

    /**
     * 整章移到另一本教材
     *
     * @param request 来源教材、章节名、目标教材必填；toTextbookSeq 为 null 表示沿用原值
     * @return 受影响的题目行数
     */
    @PostMapping("/chapter/move")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章移到另一本教材",
            description = "目标教材下已有同名章节时合并；可同时指定新教材的顺序值")
    public BaseResponse<Integer> moveChapter(@RequestBody @Valid QuizChapterMoveRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 合并与顺序值更新都在 Service 的同一条 UPDATE 里完成
        return ResultUtils.success(quizQuestionService.moveChapter(request));
    }

    /**
     * 整章删除（题目逻辑删除 + 清理错题条目）
     *
     * @param request 教材名与章节名必填
     * @return 实际删除的题目数（0 表示这一章本来就没有题）
     */
    @PostMapping("/chapter/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章删除",
            description = "删除该章全部题目并清理对应错题条目；作答记录保留")
    public BaseResponse<Integer> deleteChapter(@RequestBody @Valid QuizChapterDeleteRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 删题与清错题条目在同一事务里；返回条数供前端做「删除 N 道题」的提示
        return ResultUtils.success(quizQuestionService.deleteChapter(request));
    }

    /**
     * 教材改名 / 调序
     *
     * @param request 原教材名、新教材名必填；textbookSeq 为 null 表示不改顺序
     * @return 受影响的题目行数
     */
    @PostMapping("/textbook/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】教材改名/调序",
            description = "批量 UPDATE；改名到已存在的教材时自动合并")
    public BaseResponse<Integer> renameTextbook(@RequestBody @Valid QuizTextbookRenameRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ③ 只改教材名与顺序，章节名不动——两本书的同名章节不会因此被牵连
        return ResultUtils.success(quizQuestionService.renameTextbook(request));
    }
}

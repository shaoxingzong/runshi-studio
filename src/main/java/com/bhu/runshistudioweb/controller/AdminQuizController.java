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

// 刷题题库管理接口（管理端，全部要求 admin）
//
// 为什么路径挂在 /admin/quiz 而不是 /quiz：
// C 端的 /quiz/** 要求登录即可访问，管理端接口另起 /admin 前缀，
// 与 C 端在路径上就分开——避免将来有人为了省事把白名单写成 /quiz/**
// 而把题库的增删改一起放出去。这与 AdminPostController 的分法一致。
//
// 两类接口的分工：
// - 题目：分页 / 详情 / 增删改 / 启停 / 批量删除 / 批量导入；
// - 章节与教材：它们没有独立表（是题目上的名字列），
// 因此只有「改名 / 调序 / 移章 / 整章删除」这四种批量操作。
//
// 改题的副作用（最容易忽略的一点）：改动正确答案、题型或选项标签集合之后，
// 该题所有用户的错题连对进度会被重置（ correct_streak = 0），
// 但 status 保持不变——已消解的题不会因为管理员改了个字就退回待复习。
// 前端应在保存前提示「该题有 N 条作答，修改答案不会重算历史判定」。
@RestController
@RequestMapping("/admin/quiz")
@Tag(name = "题库管理", description = "管理员维护刷题题目、章节与教材；支持批量导入")
@RequiredArgsConstructor
public class AdminQuizController {

    private final QuizQuestionService quizQuestionService;

    // ==================== 题目 ====================

    // 题目分页（含选项与答案、作答数与正确率）
    //
    // 筛选：教材 / 章节精确、题型精确、题干关键词模糊、状态精确。
    //
    // @param request 查询条件（允许整体为 null，表示无条件查第一页）
    // @return 分页结果
    @GetMapping("/question/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目分页",
            description = "支持教材/章节/题型/关键词/状态筛选；附作答数与正确率")
    public BaseResponse<Page<QuizQuestionAdminVO>> listQuestion(QuizQuestionQueryRequest request) {
        return ResultUtils.success(quizQuestionService.listQuestionByPage(request));
    }

    // 题目详情（含选项与答案）
    //
    // @param id 题目 ID
    // @return 详情
    @GetMapping("/question/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目详情", description = "含选项与正确答案，供编辑回填")
    public BaseResponse<QuizQuestionAdminVO> getQuestion(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        return ResultUtils.success(quizQuestionService.getQuestionById(id));
    }

    // 新增题目
    //
    // @param request 新增请求
    // @return 新题目 ID
    @PostMapping("/question/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增题目",
            description = "单选恰好 1 个正确项；多选至少 2 个；选项标签须从 A 连续")
    public BaseResponse<Long> addQuestion(@RequestBody @Valid QuizQuestionAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.addQuestion(request));
    }

    // 修改题目（部分更新：null 表示不修改）
    //
    // 判定相关变更（改答案 / 改题型 / 增删选项）会重置该题所有用户的错题连对进度。
    //
    // @param request 修改请求（id 必填）
    // @return true 表示成功
    @PostMapping("/question/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】修改题目",
            description = "null 表示不修改；改答案/题型/选项标签会重置错题连对进度（不改变已消解状态）")
    public BaseResponse<Boolean> updateQuestion(@RequestBody @Valid QuizQuestionUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.updateQuestion(request));
    }

    // 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
    //
    // 作答记录保留：统计只读 result，不需要题目内容。
    //
    // @param request 删除请求（id 必填）
    // @return 实际删除条数
    @PostMapping("/question/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除题目",
            description = "逻辑删除；同事务清理错题条目；作答记录保留。只想临时下线请用「停用」")
    public BaseResponse<Integer> deleteQuestion(@RequestBody @Valid DeleteRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.deleteQuestion(request.getId()));
    }

    // 批量删除题目
    //
    // @param request 批量删除请求（ids 必填）
    // @return 实际删除条数
    @PostMapping("/question/batch-delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量删除题目", description = "整批同一事务；某条不存在不中断")
    public BaseResponse<Integer> batchDeleteQuestions(
            @RequestBody @Valid QuizQuestionBatchDeleteRequest request) {
        ThrowUtils.throwIf(request == null || request.getIds() == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.batchDeleteQuestions(request.getIds()));
    }

    // 启用 / 停用题目
    //
    // 停用是可逆的下线：不再被抽到、不再出现在错题本里，
    // 但错题条目保留——重新启用后自动回来。
    //
    // @param request 请求（id + status）
    // @return true 表示成功
    @PostMapping("/question/status")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】启用/停用题目",
            description = "停用可逆：错题条目保留，重新启用后自动回到错题本")
    public BaseResponse<Boolean> updateQuestionStatus(
            @RequestBody @Valid QuizQuestionStatusRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(
                quizQuestionService.updateStatus(request.getId(), request.getStatus()));
    }

    // 批量导入题目
    //
    // 整批全成功或全失败； dryRun=true 时只校验不落库，
    // 前端用它做「预览」。
    //
    // @param request 导入请求
    // @return 解析/导入结果，含逐行错误
    @PostMapping("/question/import")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量导入题目",
            description = "粘贴文本；支持【单选】/【多选】+ 行尾 * 或「答案：ABD」；整批全成功或全失败")
    public BaseResponse<QuizImportResultVO> importQuestions(
            @RequestBody @Valid QuizQuestionImportRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.importQuestions(request));
    }

    // ==================== 章节 / 教材 ====================

    // 章节列表（管理端口径：含停用题）
    //
    // 与 C 端的 /quiz/catalog 口径不同：那边只数启用题（用户看到的
    // 是「能刷多少」），这里数全部题（管理员看的是存量），并单独给启用数。
    //
    // @return 章节列表
    @GetMapping("/chapter/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节列表",
            description = "含停用题；同时给出启用数，便于判断「共 50 题（启用 48）」")
    public BaseResponse<List<QuizChapterAdminVO>> listChapters() {
        return ResultUtils.success(quizQuestionService.listChaptersForAdmin());
    }

    // 章节改名 / 调序
    //
    // 改名到已存在的章节时合并（不报错）：用户想的大概率就是合并。
    //
    // @param request 改名请求
    // @return 受影响的题目行数
    @PostMapping("/chapter/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节改名/调序",
            description = "批量 UPDATE；改名到已存在的章节时自动合并")
    public BaseResponse<Integer> renameChapter(@RequestBody @Valid QuizChapterRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.renameChapter(request));
    }

    // 整章移到另一本教材
    //
    // @param request 移动请求
    // @return 受影响的题目行数
    @PostMapping("/chapter/move")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章移到另一本教材",
            description = "目标教材下已有同名章节时合并；可同时指定新教材的顺序值")
    public BaseResponse<Integer> moveChapter(@RequestBody @Valid QuizChapterMoveRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.moveChapter(request));
    }

    // 整章删除（题目逻辑删除 + 清理错题条目）
    //
    // 本模块唯一会批量删数据的接口，前端必须二次确认并显示题数。
    //
    // @param request 删除请求
    // @return 实际删除的题目数
    @PostMapping("/chapter/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章删除",
            description = "删除该章全部题目并清理对应错题条目；作答记录保留")
    public BaseResponse<Integer> deleteChapter(@RequestBody @Valid QuizChapterDeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.deleteChapter(request));
    }

    // 教材改名 / 调序
    //
    // @param request 改名请求
    // @return 受影响的题目行数
    @PostMapping("/textbook/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】教材改名/调序",
            description = "批量 UPDATE；改名到已存在的教材时自动合并")
    public BaseResponse<Integer> renameTextbook(@RequestBody @Valid QuizTextbookRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(quizQuestionService.renameTextbook(request));
    }
}

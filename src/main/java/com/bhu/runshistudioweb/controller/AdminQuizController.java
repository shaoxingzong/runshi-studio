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
 * 改一道题的答案会影响所有用户的判分与错题本，因此按角色卡死；C 端 /quiz/** 只挂登录、
 * 成员身份由 Service 认表。章节与教材只是题目上的名字列，故只有改名 / 调序 / 移章 / 整章删除。
 *
 * 改答案、题型或选项标签会重置连对进度，但不改 status。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/admin/quiz")
@Tag(name = "题库管理", description = "管理员维护刷题题目、章节与教材；支持批量导入")
@RequiredArgsConstructor
public class AdminQuizController {

    private final QuizQuestionService quizQuestionService;

    // ==================== 题目 ====================

    /**
     * 题目分页（含选项与答案、作答数与正确率）
     *
     * 管理端必须看到答案才能核对题目，与 C 端出题 VO 不能共用。作答统计由一次分组查询填充，
     * 逐行统计就是 N+1。不加 {@code @Valid}：GET 校验失败会变成 B0001，页码传错不该让查询失败。
     *
     * @param request 教材、章节、题型、状态精确，题干模糊 + 分页；允许整体为 null
     * @return 分页结果（QuizQuestionAdminVO，含选项、正确答案与作答统计）
     */
    @GetMapping("/question/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目分页",
            description = "支持教材/章节/题型/关键词/状态筛选；附作答数与正确率")
    public BaseResponse<Page<QuizQuestionAdminVO>> listQuestion(@Valid QuizQuestionQueryRequest request) {
        // ① Spring 按字段名绑定查询串；一个参数都不传时字段全为 null，Service 按「无条件、第一页」处理；
        //    @Valid 只负责题干关键词长度（@Size max=64），超长返回 A0401
        // ②③ 查询条件全为 null 也合法（等价于无条件），归一化交给 Service，Controller 不重复一遍
        return ResultUtils.success(quizQuestionService.listQuestionByPage(request));
    }

    /**
     * 题目详情（含选项与答案）
     *
     * 编辑弹窗只拉这一条，不必为了改一道题去翻列表找它在第几页。
     *
     * @param id 题目 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return QuizQuestionAdminVO（含选项与正确答案）
     */
    @GetMapping("/question/detail")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目详情", description = "含选项与正确答案，供编辑回填")
    public BaseResponse<QuizQuestionAdminVO> getQuestion(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何行，查库只会返回一个误导性的「题目不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // ③ 管理端详情含答案，因此不能把这个 VO 复用到任何 C 端接口上
        return ResultUtils.success(quizQuestionService.getQuestionById(id));
    }

    /**
     * 新增题目
     *
     * 跨字段规则只能由 Service 整体校验：单选恰好 1 个正确项、多选至少 2 个、选项标签从 A 连续、
     * 正确项必须属于选项集合。注解只管字段级的事。
     *
     * @param request 题干、题型、选项列表必填；标签须从 A 连续，跳号会让判分无法排查
     * @return 新题目 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/question/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增题目",
            description = "单选恰好 1 个正确项；多选至少 2 个；选项标签须从 A 连续")
    public BaseResponse<Long> addQuestion(@RequestBody @Valid QuizQuestionAddRequest request) {
        // ① 空 body 时 @Valid 整条链路都不生效，必须先挡
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 跨字段规则（正确项个数、标签连续性）只能由 Service 整体校验
        return ResultUtils.success(quizQuestionService.addQuestion(request));
    }

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * 成立前提是 Service 用旧值补齐缺失字段后再整体校验，否则「只改解析」会报选项为空。
     * 改答案、题型或增删选项会重置该题所有用户的连对进度——旧题积累的「连对几次」不再可信。
     *
     * @param request id 必填，其余字段为 null 表示保持原值
     * @return true 表示修改成功，失败抛异常
     */
    @PostMapping("/question/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】修改题目",
            description = "null 表示不修改；改答案/题型/选项标签会重置错题连对进度（不改变已消解状态）")
    public BaseResponse<Boolean> updateQuestion(@RequestBody @Valid QuizQuestionUpdateRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 重置错题进度这件事在 Service 的同一事务里完成，Controller 不感知
        return ResultUtils.success(quizQuestionService.updateQuestion(request));
    }

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * 题目逻辑删除保住审计线索，但错题条目必须清理，否则错题本会渲染出「题目已不存在」的空行。
     * 作答记录保留——统计只读判定结果，删了反而算不出历史正确率。临时下线请用「停用」。
     *
     * @param request id 必填且为正整数
     * @return 实际删除条数（正常为 1），与批量删除保持同一返回形态
     */
    @PostMapping("/question/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除题目",
            description = "逻辑删除；同事务清理错题条目；作答记录保留。只想临时下线请用「停用」")
    public BaseResponse<Integer> deleteQuestion(@RequestBody @Valid DeleteRequest request) {
        // ① 请求体与 id 都要判：空 body 时取 id 的那一步同样会炸
        ThrowUtils.throwIf(request == null || request.getId() == null, ErrorCode.PARAMS_ERROR);
        // ②③ 返回的是「删了几条」而不是布尔值，与批量删除接口保持同一套返回值形态
        return ResultUtils.success(quizQuestionService.deleteQuestion(request.getId()));
    }

    /**
     * 批量删除题目
     *
     * 单独建带 ids 的 DTO 而不复用单条删除的 DeleteRequest：后者契约就是「一个 id」。
     * 整批同一事务，某条不存在不中断——批量删除的语义是「把这些都删掉」。
     *
     * @param request ids 必填且非 null，至少一条（空列表由 Service 拦成 A0401）
     * @return 实际删除条数，可能小于 ids 的个数
     */
    @PostMapping("/question/batch-delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】批量删除题目", description = "整批同一事务；某条不存在不中断")
    public BaseResponse<Integer> batchDeleteQuestions(
            @RequestBody @Valid QuizQuestionBatchDeleteRequest request) {
        // ① ids 为 null 时遍历会 NPE，因此这里必须与请求体一起判
        ThrowUtils.throwIf(request == null || request.getIds() == null, ErrorCode.PARAMS_ERROR);
        // ②③ 整批同一事务，返回条数可能小于传入个数（部分已被删除）
        return ResultUtils.success(quizQuestionService.batchDeleteQuestions(request.getIds()));
    }

    /**
     * 启用 / 停用题目
     *
     * 停用是可逆下线：不再被抽到、不再出现在错题本，但条目保留，重新启用后自动回来。
     * 与删除的区别在恢复性——删除会连带清掉错题条目。
     *
     * @param request id 必填，status 取 0（启用）或 1（停用）
     * @return true 表示状态已改成目标值
     */
    @PostMapping("/question/status")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】启用/停用题目",
            description = "停用可逆：错题条目保留，重新启用后自动回到错题本")
    public BaseResponse<Boolean> updateQuestionStatus(
            @RequestBody @Valid QuizQuestionStatusRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 只把 id 与 status 交给 Service：方法签名即契约，DTO 的其余字段与它无关
        return ResultUtils.success(
                quizQuestionService.updateStatus(request.getId(), request.getStatus()));
    }

    /**
     * 批量导入题目
     *
     * 收粘贴文本而非上传文件：来源多是复制一段题目，文本解析的错误还能精确到行号。
     * 整批全成功或全失败——「导入了一半」会让管理员无法判断哪些进了。dryRun 必须在落库前判断。
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
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 整批原子性由 Service 保证，Controller 不在这里做「逐条重试」之类的补偿
        return ResultUtils.success(quizQuestionService.importQuestions(request));
    }

    // ==================== 章节 / 教材 ====================

    /**
     * 章节列表（管理端口径：含停用题）
     *
     * 与 C 端 /quiz/catalog 口径不同：那边只数启用题，这里数全部并单独给出启用数，
     * 管理端才能显示「共 50 题（启用 48）」。一次 GROUP BY 查完再挂成两层，不做 N+1。
     *
     * @return 章节列表（QuizChapterAdminVO，含题目总数与启用数）
     */
    @GetMapping("/chapter/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节列表",
            description = "含停用题；同时给出启用数，便于判断「共 50 题（启用 48）」")
    public BaseResponse<List<QuizChapterAdminVO>> listChapters() {
        // ① 无入参：章节与教材是全量聚合的结果，不需要分页
        // ②③ 一次聚合查完，不写「先查教材、再逐个查章节」的 N+1
        return ResultUtils.success(quizQuestionService.listChaptersForAdmin());
    }

    /**
     * 章节改名 / 调序
     *
     * 章节只是题目上的两个名字列，改名必须是一条批量 UPDATE，逐条更新会留下「半章改了名」。
     * 改到已存在的章节时合并；oldName 与 newName 相同不算错误，那是「只调序」的合法用法。
     *
     * @param request 教材名、原章节名、新章节名必填；chapterSeq 为 null 表示不改顺序
     * @return 受影响的题目行数
     */
    @PostMapping("/chapter/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】章节改名/调序",
            description = "批量 UPDATE；改名到已存在的章节时自动合并")
    public BaseResponse<Integer> renameChapter(@RequestBody @Valid QuizChapterRenameRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 一条批量 UPDATE 在事务里原子完成，返回受影响行数供前端展示影响面
        return ResultUtils.success(quizQuestionService.renameChapter(request));
    }

    /**
     * 整章移到另一本教材
     *
     * 目标教材下已有同名章节时合并而不是报错。移动后要带上新教材的顺序值——章节顺序是
     * 「教材内」的概念，沿用旧值会排序错乱。来源与目标相同时应拒绝，否则 UPDATE 什么都没改。
     *
     * @param request 来源教材、章节名、目标教材必填；toTextbookSeq 为 null 表示沿用原值
     * @return 受影响的题目行数
     */
    @PostMapping("/chapter/move")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章移到另一本教材",
            description = "目标教材下已有同名章节时合并；可同时指定新教材的顺序值")
    public BaseResponse<Integer> moveChapter(@RequestBody @Valid QuizChapterMoveRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 合并与顺序值更新都在 Service 的同一条 UPDATE 里完成
        return ResultUtils.success(quizQuestionService.moveChapter(request));
    }

    /**
     * 整章删除（题目逻辑删除 + 清理错题条目）
     *
     * 本模块唯一会批量删数据的接口，前端必须二次确认。删题与清错题条目须同一事务，
     * 否则会留下指向已删题目的条目；作答记录保留。
     *
     * @param request 教材名与章节名必填
     * @return 实际删除的题目数（0 表示这一章本来就没有题）
     */
    @PostMapping("/chapter/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】整章删除",
            description = "删除该章全部题目并清理对应错题条目；作答记录保留")
    public BaseResponse<Integer> deleteChapter(@RequestBody @Valid QuizChapterDeleteRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 删题与清错题条目在同一事务里；返回条数供前端做「删除 N 道题」的提示
        return ResultUtils.success(quizQuestionService.deleteChapter(request));
    }

    /**
     * 教材改名 / 调序
     *
     * 教材同样没有独立表，改名即一条批量 UPDATE，改到已存在的名字时合并。
     * 与章节改名的区别是影响面：这里会连带整本书的所有章节，前端要提示。
     *
     * @param request 原教材名、新教材名必填；textbookSeq 为 null 表示不改顺序
     * @return 受影响的题目行数
     */
    @PostMapping("/textbook/rename")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】教材改名/调序",
            description = "批量 UPDATE；改名到已存在的教材时自动合并")
    public BaseResponse<Integer> renameTextbook(@RequestBody @Valid QuizTextbookRenameRequest request) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ②③ 只改教材名与顺序，章节名不动——两本书的同名章节不会因此被牵连
        return ResultUtils.success(quizQuestionService.renameTextbook(request));
    }
}

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
 * 鉴权级别为什么是 {@code @SaCheckRole(ADMIN)}：题库是刷题模块的数据源，
 * 改一道题的答案会影响所有用户的判分与错题本，属于全局性写操作。
 * 只要求「已登录」等于把题库的增删改交给任何注册用户，这是不能接受的。
 * 注意它与 C 端 /quiz/** 的差别：那边只挂登录注解、成员身份由 Service 认成员表判定；
 * 这边是管理动作，按角色判定。
 *
 * 为什么路径挂在 /admin/quiz 而不是 /quiz：
 * C 端的 /quiz/** 要求登录即可访问，管理端接口另起 /admin 前缀，
 * 与 C 端在路径上就分开——避免将来有人为了省事把白名单写成 /quiz/**
 * 而把题库的增删改一起放出去。这与 AdminPostController 的分法一致。
 *
 * 两类接口的分工：
 * - 题目：分页 / 详情 / 增删改 / 启停 / 批量删除 / 批量导入；
 * - 章节与教材：它们没有独立表（是题目上的名字列），
 * 因此只有「改名 / 调序 / 移章 / 整章删除」这四种批量操作，没有「新建一个空章节」的接口。
 *
 * 改题的副作用（最容易忽略的一点）：改动正确答案、题型或选项标签集合之后，
 * 该题所有用户的错题连对进度会被重置（correct_streak 归零），
 * 但 status 保持不变——已消解的题不会因为管理员改了个字就退回待复习。
 * 前端应在保存前提示「该题有 N 条作答，修改答案不会重算历史判定」。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/admin/quiz/question/page。
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
     * <p>为什么这样设计：管理端的题目列表必须看到答案，否则管理员无法核对题目录得对不对；
     * 而 C 端出题接口的 VO 结构上没有答案字段，两者不能共用一个 VO。
     * 作答数与正确率由一次分组查询填充，不是逐行统计——逐行就是 N+1
     * （查 1 次列表，再对每条记录各查 1 次，条数越多查询越多）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 QuizQuestionQueryRequest。它不是请求体，
     *     所以不需要 {@code @RequestBody}；一个参数都不传时字段全为 null，
     *     Service 会按「无条件、第一页」处理；</li>
     *     <li>② 交给 Service：组装筛选条件、按白名单排序、分页查询并补作答统计；</li>
     *     <li>③ 包成统一响应体返回。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}，也不在 Controller 里判「对象为 null」。
     * 查询参数走 GET，校验失败抛的是 BindException，全局异常处理器没接它，
     * 会变成 B0001「系统错误」；而「页码传错」本来也不该让整个查询失败。
     * 真正需要报错的只有「题型取值非法」这类闭集参数，由 Service 抛 A0401。
     *
     * @param request 查询条件：教材与章节精确、题型精确、题干关键词模糊、状态精确、分页；
     *                允许整体为 null（表示无条件查第一页）
     * @return 分页结果，记录为 QuizQuestionAdminVO（含选项、正确答案与作答统计）；
     * 无数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 题型或状态取值非法（A0401）时抛出
     */
    @GetMapping("/question/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】题目分页",
            description = "支持教材/章节/题型/关键词/状态筛选；附作答数与正确率")
    public BaseResponse<Page<QuizQuestionAdminVO>> listQuestion(QuizQuestionQueryRequest request) {
        // ① Spring 按字段名绑定查询串；一个参数都不传时字段全为 null，Service 按「无条件、第一页」处理
        // ②③ 查询条件全为 null 也合法（等价于无条件），归一化交给 Service，Controller 不重复一遍
        return ResultUtils.success(quizQuestionService.listQuestionByPage(request));
    }

    /**
     * 题目详情（含选项与答案）
     *
     * <p>为什么这样设计：编辑弹窗要回填选项与正确答案，列表 VO 虽然也带答案，
     * 但那是「一页十条」的批量视图；单条详情单独一个接口，前端打开弹窗时只拉这一条，
     * 不必为了编辑一道题去翻列表找它在第几页。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何题目，直接给 A0401，
     *     省掉一次数据库往返，也避免把「参数错」伪装成「题目不存在」；</li>
     *     <li>③ 调 Service 取详情并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里用 long 而不是 Long，也没有 required = false。
     * 传非数字（例如 ?id=abc）时 Spring 抛类型转换异常，全局异常处理器把它兜成 B0001 而不是 A0401。
     * 编辑弹窗总是带着 id 调，这个粗糙边界可以接受；但若 id 来自用户手输的链接，
     * 应改用包装类型 Long 并在方法内显式判空，才能给出准确的参数提示。
     *
     * @param id 题目 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 详情（含选项与正确答案），记录为 QuizQuestionAdminVO
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、题目不存在（A0402）时抛出
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
     * <p>为什么这样设计：题目的跨字段规则没法用注解表达——「单选恰好 1 个正确项、
     * 多选至少 2 个、选项标签必须从 A 开始连续、正确项必须属于选项集合」，
     * 这些约束相互牵连，只能由 Service 在拿到完整对象后整体校验。
     * 注解只负责字段级的事（题干非空、选项列表非空），两层校验分工明确。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束，请求体整体为 null 时它不触发，
     *     后面取字段会抛空指针被兜成 B0001；</li>
     *     <li>② 交给 Service：校验跨字段规则、把选项列表编码成存储形态、落库；</li>
     *     <li>③ 返回新题目 ID（雪花算法生成的 19 位长整型，接口层序列化成字符串防前端丢精度）。</li>
     * </ol>
     *
     * <p>容易踩的坑：选项标签必须从 A 开始连续（A、B、C……），不能出现 A、C、D 这种跳号。
     * 这不是格式洁癖——判分时标签直接参与比较，跳号会让「用户选了 C」与
     * 「正确答案记作 B」这类错位变得无法排查。
     *
     * @param request 新增请求：题干、题型、选项列表必填；单选恰好 1 个正确项、多选至少 2 个
     * @return 新题目 ID（19 位，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 字段或跨字段规则不满足（A0401）、入库失败（B0002）时抛出
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
     * <p>为什么这样设计：部分更新让前端「只改解析」时不必回传整个选项列表。
     * 它成立的前提是 Service 用旧值把缺失字段补齐后再整体校验——
     * 否则「只改解析」会因为选项为空而报「选项不能为空」，这显然不是用户想要的。
     * 另外，判定相关变更（改答案 / 改题型 / 增删选项）会重置该题所有用户的错题连对进度：
     * 题目都变了，基于旧题积累的「连续答对几次」就不再可信。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：取旧实体 → 用非 null 字段覆盖 → 用旧值补齐后整体校验
     *     → 检测判定相关变更 → 写库（必要时同事务重置错题进度）；</li>
     *     <li>③ 返回布尔值。它是「更新命中了一行」的意思，失败会抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：重置错题进度但不改变 status。已消解的题不会因为管理员改了个字
     * 就退回待复习——那是「用户已掌握」的事实，不该被题目编辑抹掉。
     * 若把重置写成「status 也归零」，用户会突然在错题本里看到一堆以为自己已经掌握了的题。
     *
     * @param request 修改请求：id 必填；其余字段为 null 表示保持原值
     * @return true 表示修改成功（失败抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 题目不存在（A0402）、校验不通过（A0401）、入库失败（B0002）时抛出
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
     * <p>为什么这样设计：逻辑删除（只把 deleted_at 标成非 0，行仍留在表里，
     * 查询时由框架自动追加过滤）能保住审计线索，所以题目本身不物理删除。
     * 但错题条目必须清理：题目没了，条目无处可去，留着只会在错题本里
     * 渲染出一条「题目已不存在」的空行。作答记录则保留——统计只读判定结果，
     * 不需要题目内容，删了反而让历史正确率算不出来。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，请求体与 id 一起判——空 body 时 {@code @Valid} 不会生效；</li>
     *     <li>② 交给 Service：先查题目是否存在（不存在直接报 A0402），
     *     再同事务清理该题的全部错题条目、逻辑删除题目；</li>
     *     <li>③ 返回实际删除条数。正常情况下是 1；返回值类型之所以是 int，
     *     是为了与批量删除接口保持一致，让前端可以用同一套逻辑处理两个接口的返回值。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里 import 的是通用 DTO {@code model.dto.common.DeleteRequest}，
     * 与帖子模块里那个同名的 DeleteRequest 不是同一个类。两者字段相同，
     * 换错 import 编译器不会报错，只会在将来某一方加字段时悄悄错位。
     * 另外「临时下线」应该用停用而不是删除——删除不可逆，错题条目会被清掉。
     *
     * @param request 删除请求：id 必填且为正整数
     * @return 实际删除的题目数（正常为 1）；题目不存在时抛 A0402 而不是返回 0
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、题目不存在（A0402）时抛出
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
     * <p>为什么这样设计：单独建一个带 ids 列表的 DTO，而不是复用单条删除的 DeleteRequest——
     * 后者的契约就是「一个 id」，把列表塞进同一个类会让两种语义共用一个字段名，
     * 读代码时得靠上下文猜。整批在一个事务里：要么全删、要么全不删，
     * 不会出现「删了一半，另一半因为某条异常而留在库里」这种无法判断进度的状态。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空，请求体与 ids 一起判。为什么要判 ids——空 body 时 {@code @Valid} 不生效，
     *     而 Service 直接遍历 ids 会抛空指针被兜成 B0001；</li>
     *     <li>② 交给 Service：逐条逻辑删除并清理对应错题条目，整批同一事务；</li>
     *     <li>③ 返回实际删除条数。某条不存在不会中断——批量删除的语义是
     *     「把这些都删掉」，其中一条已被别人删掉不该让整批失败。</li>
     * </ol>
     *
     * <p>容易踩的坑：ids 为空列表与 ids 为 null 是两件事，但两者的结论一样——都算参数错误。
     * 这里只挡 null（Controller 层的判空职责到此为止），空列表由 Service 拦成
     * A0401「请选择要删除的题目」。为什么不在这里也判一次：判空与业务校验的边界要清楚，
     * 「至少选一条」是业务规则，放在 Service 才能被其它入口复用。
     *
     * @param request 批量删除请求：ids 必填且不能为 null，且至少包含一条
     * @return 实际删除的题目条数（可能小于 ids 的个数）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * ids 为 null 或空列表（A0401）时抛出
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
     * <p>为什么这样设计：停用是可逆的下线——不再被抽到、不再出现在错题本里，
     * 但错题条目保留，重新启用后自动回来。它和删除解决的是两个不同的问题：
     * 「这题先别用」用停用，「这题彻底不要了」才删除。若只有删除，
     * 管理员面对一道有争议的题就只能二选一：要么留着让用户刷到，要么删掉不可恢复。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 把 id 与 status 两个字段拆开传给 Service，而不是整个 DTO 传进去——
     *     Service 的方法签名就说明了「它只关心这两个值」，DTO 的其余字段与它无关；</li>
     *     <li>③ 返回布尔值，表示状态确实被改成了目标值。</li>
     * </ol>
     *
     * <p>容易踩的坑：停用不清理任何数据，这一点很容易被误解成「停用等于软删除」。
     * 二者的差别在恢复性上：停用的题重新启用后，用户已积累的错题条目会原样回来；
     * 删除则把条目一并清掉，恢复只能靠数据库回滚。
     *
     * @param request 请求：id 必填且为正整数，status 取 0（启用）或 1（停用）
     * @return true 表示状态已改成目标值（失败会抛异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 题目不存在（A0402）、status 取值非法（A0401）时抛出
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
     * <p>为什么这样设计：导入的是粘贴文本而不是上传文件。题库的来源几乎都是
     * 从文档或网页里复制一段题目，粘贴比「下载模板再上传」少两步；
     * 而且文本解析的错误可以精确到行号，比「上传的文件解析失败」有用得多。
     * 整批全成功或全失败：任何一条解析或校验失败，整批都不落库——
     * 「导入了一半」会让管理员无法判断哪些进了、哪些没进，只能全部删掉重来。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：解析文本 → 逐条校验 → dryRun 为 true 时只返回校验结果，
     *     否则整批落库。dryRun 让前端能做「先预览再确认」的两步操作；</li>
     *     <li>③ 返回导入结果（含逐行错误）。它是统计对象而不是布尔值——
     *     批量操作里「部分成功」才是常态，只给成败会让管理员无从判断。</li>
     * </ol>
     *
     * <p>容易踩的坑：dryRun 的判断必须发生在落库前，而不是「先落库再按 dryRun 回滚」。
     * 回滚方案看起来更省代码，但它会把整批数据真的写进数据库再删掉，
     * 期间任何并发查询都可能读到这批数据，自增主键也会被白白消耗。
     *
     * @param request 导入请求：教材名、章节名、待解析文本必填；dryRun 为 true 时只校验不落库
     * @return 解析 / 导入结果，含成功条数与逐行错误信息
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 文本格式非法、题目数量超上限（A0401）时抛出
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
     * <p>为什么这样设计：与 C 端的 /quiz/catalog 口径不同。那边只数启用题
     * （用户看到的是「这一章能刷多少」），这里数全部题（管理员看的是存量），
     * 并单独给出启用数，管理端就能显示「共 50 题（启用 48）」。
     * 共用一个 VO 就必然有一端拿到的数字是错的，而这种错不会报错、只会让人困惑。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参——章节与教材没有独立表，是全量聚合的结果，不需要分页；</li>
     *     <li>② 调 Service 取聚合结果，一次 GROUP BY 查完再在内存里挂成两层结构；</li>
     *     <li>③ 包成统一响应体。列表为空时返回空列表，不会是 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要「先查教材、再逐个查章节」。那是标准 N+1，
     * 教材一多，这个接口就会从毫秒级掉到秒级；而且两次查询之间若有题目新增，
     * 章节的题数还会对不上总数。
     *
     * @return 章节列表，记录为 QuizChapterAdminVO（含题目总数与启用数）；
     * 题库为空时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）时抛出
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
     * <p>为什么这样设计：章节是几百道题上的两个名字列，没有独立表。
     * 因此改名必须是一条批量 UPDATE，而不是逐条更新——逐条既慢，
     * 又可能中途失败留下「半章改了名、半章没改」的脏状态。
     * 改名到已存在的章节时合并（两章并成一章）而不是报错：
     * 用户想的大概率就是合并，报错会逼他先把两章改成不同名字再改回来。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验原章节存在、执行一条带条件的 UPDATE、必要时合并；</li>
     *     <li>③ 返回受影响的题目行数。这个数字对管理员有意义——「改了 37 道题」
     *     比「改名成功」更能说明这次操作的影响面。</li>
     * </ol>
     *
     * <p>容易踩的坑：oldName 与 newName 相同时不是错误，而是「只调序」的合法用法
     * （配合 chapterSeq 使用）。若在校验里把「新旧同名」判成非法，排序功能就废了。
     *
     * @param request 改名请求：教材名、原章节名、新章节名必填；chapterSeq 为 null 表示不改顺序
     * @return 受影响的题目行数（0 表示原章节不存在，此时 Service 会抛异常而不是返回 0）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、章节不存在（A0402）时抛出
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
     * <p>为什么这样设计：典型场景是导入时把章节放错了教材，或者一本教材被拆成两本。
     * 目标教材下已有同名章节时合并而不是报错——报错会让用户不得不先把两章改成不同名字
     * 再移一次，徒增操作。移动后教材顺序值也要跟着改：章节顺序是「教材内」的概念，
     * 换了教材就得用新教材的顺序值，否则排序会错乱。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验来源章节存在、按「来源教材 + 章节名」批量改成目标教材，
     *     必要时合并同名章节并更新顺序值；</li>
     *     <li>③ 返回受影响的题目行数，即「这次移动带走了多少道题」。</li>
     * </ol>
     *
     * <p>容易踩的坑：来源教材与目标教材相同时应当拒绝（或等价于只调序）。
     * 若不做判断，UPDATE 会把所有行的 textbook_name 写成同一个值，
     * 看起来「成功」但实际什么都没变，管理员会以为操作没生效而反复点击。
     *
     * @param request 移动请求：来源教材、章节名、目标教材必填；toTextbookSeq 为 null 表示沿用原值
     * @return 受影响的题目行数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、来源章节不存在（A0402）时抛出
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
     * <p>为什么这样设计：这是本模块唯一会批量删数据的接口，因此前端必须二次确认，
     * 并在确认框里显示「该章有 N 道题，其中 M 道有作答记录」。删完之后错题本里
     * 对应条目也会一并清理（题目没了，条目无处可去），但作答记录保留
     * （统计只读判定结果，不需要题目内容）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：按「教材 + 章节名」定位整章，逻辑删除题目、
     *     同事务清理错题条目。两步必须同一事务，否则会留下指向已删题目的错题条目；</li>
     *     <li>③ 返回实际删除的题目数，供前端提示「删除 N 道题」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本接口按「教材名 + 章节名」定位，而不是按 id 定位——
     * 章节没有独立表，它只是题目上的两个名字列。因此教材或章节改名之后，
     * 旧的请求参数就再也定位不到任何题；前端必须每次从章节列表里取最新名字再提交。
     *
     * @param request 删除请求：教材名与章节名必填
     * @return 实际删除的题目数（0 表示这一章本来就没有题）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、章节不存在（A0402）时抛出
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
     * <p>为什么这样设计：教材同样没有独立表，它就是题目上的 textbook_name 与 textbook_seq 两列。
     * 因此「改名」= 一条批量 UPDATE；改到已存在的名字时合并。
     * 与章节改名的唯一区别是作用范围：教材改名会连带把这本书下所有章节一起改名，
     * 影响面是整本书而不是一章，因此前端要提示「将影响本书全部章节」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：校验原教材存在、批量改 textbook_name 与 textbook_seq；</li>
     *     <li>③ 返回受影响的题目行数，管理员据此判断影响面。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只改教材名与顺序，不动章节名。若有人为了「省事」
     * 在这里顺手把章节名也拼上教材前缀，两本书的同名章节会在合并时撞在一起，
     * 变成一次不可预料的批量改章。
     *
     * @param request 改名请求：原教材名、新教材名必填；textbookSeq 为 null 表示不改顺序
     * @return 受影响的题目行数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法（A0401）、教材不存在（A0402）时抛出
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

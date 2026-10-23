package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.quiz.QuizAnswerRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizBatchRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizSkipRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import com.bhu.runshistudioweb.model.vo.quiz.QuizBatchVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOverviewVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizVerdictVO;

/**
 * 刷题服务（C 端：出题、判分、跳过、概览）
 *
 * 准入规则：只有在队的工作室成员能用，判定权威是 studio_member 表
 * （ user_id 命中且 member_status=0），不是 sys_user.user_role。
 * 这与考勤模块完全同源：角色是权限标签，档案才是业务事实，两者可能不同步。
 * 非成员调用任意方法都抛 A0301，连教材列表都不给——他没有刷题资格，
 * 给了只会是一份空数据加一堆困惑。
 *
 * 判分在服务端，答案不下发：题目 VO 结构上没有 correctLabels / analysis
 * （见 QuizQuestionVO 的类注释），答案只在 submitAnswer 的返回值里出现。
 * 这是本模块的安全底线。
 *
 * 批次不落库：题目不含答案，整批交给前端持有即可；服务端只需要
 * batchId 做幂等。代价是换设备不能续答（刷题不是考试，重开成本为 0），
 * 同设备刷新由前端 sessionStorage 兜住。
 *
 * 约定：参数不合法 / 业务不允许时抛 BusinessException，不返回 null。
 */
public interface QuizPracticeService extends IService<StudioQuizAnswer> {

    /**
     * 开一批题目
     *
     * 三种模式的必填参数不同（顺序模式必须指定教材+章节，其余模式都可空），
     * 由实现校验并给出「哪个模式缺了哪个参数」的明确提示。
     *
     * @param request 开批请求
     * @return 批次 ID + 题目列表（不含答案）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员、参数不合法、
     * 章节已刷完、错题本为空、范围内无题时抛出
     */
    QuizBatchVO startBatch(QuizBatchRequest request);

    /**
     * 提交作答（幂等）
     *
     * 幂等的实现：先 insert，撞唯一键 uk_user_batch_question 时
     * 重读已有记录、原样返回首次判定——断网重试是正常现象，
     * 让用户看到「本题已作答」的红条比看到判定结果更糟。
     *
     * @param request 作答请求
     * @return 判定结果（含正确答案与解析）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员、题目不存在、
     * 标签非法、单选传了多个标签时抛出
     */
    QuizVerdictVO submitAnswer(QuizAnswerRequest request);

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * 它落一条 result=3 的记录：否则断点续答无法区分「处理过」与「还没轮到」，
     * 幂等也没法做。但响应里什么都没有——否则跳过按钮就成了看答案的捷径。
     *
     * @param request 跳过请求
     * @return true 表示记录成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员、题目不存在时抛出
     */
    boolean skipQuestion(QuizSkipRequest request);

    /**
     * 学习概览（四个数字）
     *
     * 实现上只查两次：一次 COUNT + SUM(CASE...) 拿作答与正确数，
     * 一次 GROUP BY status 拿错题两数。绝不写「四个指标四次查询」。
     *
     * @return 概览
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员时抛出
     */
    QuizOverviewVO getOverview();

    /**
     * 校验当前登录用户是在队成员，并返回其账号 ID
     *
     * 为什么要把这个校验暴露出来：教材/章节树（ /quiz/catalog）的数据
     * 由 QuizQuestionService 提供，但准入规则属于本服务。
     * 如果 Controller 只挂 @SaCheckLogin 而不做成员校验，
     * 一个「只有账号、没有成员档案」的普通用户就能读到整个题库目录——
     * 数据本身不敏感，但这是准入规则的漏洞：他本不该进入这个模块的任何入口。
     *
     * 与其在 Controller 里复制一遍「查 studio_member 表」的逻辑（两份规则迟早漂移），
     * 不如把这个判定暴露成公开方法，让所有 /quiz/** 入口共用同一个实现。
     *
     * @return 当前登录账号 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）或非在队成员（A0301）时抛出
     */
    long requireActiveMemberId();

    /**
     * 我的累计作答与答对（供判分后即时回填，避免前端再调一次概览接口）
     *
     * @param userId 用户 ID
     * @return 长度 2 的数组：[累计作答, 累计答对]
     */
    int[] countAnsweredAndCorrect(long userId);

    /**
     * 分页查某用户在某批次的作答记录（供测试与排查使用）
     *
     * @param userId 用户 ID
     * @param batchId 批次 ID
     * @return 作答记录列表；无记录时返回空列表
     */
    Page<StudioQuizAnswer> listByBatch(long userId, long batchId);
}

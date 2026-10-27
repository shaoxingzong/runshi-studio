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
 * 准入规则：只有在队的工作室成员能用，判定权威是 studio_member 表而不是 sys_user.user_role，
 * 与考勤模块同源。非成员调用任意方法都抛 A0301。
 *
 * 判分在服务端、答案不下发：题目 VO 结构上没有 correctLabels / analysis，
 * 答案只在 submitAnswer 的返回值里出现。这是本模块的安全底线，靠的是「目标类没有这个字段」，
 * 而不是「记得在转换时删掉」。
 *
 * 批次不落库：服务端只用 batchId 做幂等。代价是换设备不能续答（刷题不是考试，重开成本为 0），
 * 同设备刷新由前端 sessionStorage 兜住。
 *
 * @author shaoshing
 */
public interface QuizPracticeService extends IService<StudioQuizAnswer> {

    /**
     * 开一批题目
     *
     * 三种模式的必填参数不同（顺序模式必须给教材 + 章节，其余可只给范围或完全不给）。
     * 这种「按模式决定必填性」的规则没法用 DTO 注解表达，只能由实现校验，
     * 并且提示必须写清「哪个模式缺了哪个参数」。批次 ID 由服务端生成，
     * 让前端传等于把「本轮是哪几题」的解释权交出去，前端就能伪造批次去重放别的题的判定。
     *
     * 坑：本方法不幂等，每调用一次就生成新的 batchId，不能靠重试恢复一个丢掉的批次。
     * 返回的 total 取组装后的真实条数而不是请求里的 size，否则题量不足时进度条永远走不满。
     *
     * @param request 开批请求，不能为 null（为 null 抛 A0401）；mode 必填，
     *                0-顺序、1-错题重刷、2-随机；size 可为 null（用配置的默认题量），
     *                restart 为 null 时按 false 处理
     * @return 批次对象，含 batchId、模式、实际题量与题目列表（结构里没有答案字段）
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、
     *         模式非法或只给章节没给教材（A0401）、本章已刷完 / 错题本为空 / 范围内无题（A0402）
     */
    QuizBatchVO startBatch(QuizBatchRequest request);

    /**
     * 提交作答（幂等）
     *
     * 判分必须在服务端：出题 VO 里没有答案字段，前端拿不到正确答案，也无法自己算对错。
     * 幂等靠唯一键 uk_user_batch_question：撞键时重读已有记录、原样返回首次判定，
     * 断网重试是移动端的常态。重复提交时错题本的连对进度也不会再推进一次，
     * 否则用户双击一下就能白赚一次连对。
     *
     * 坑：本方法不校验 questionId 是否属于 batchId——服务端不持久化批次内容，无法校验。
     * 最坏影响是用户给自己刷出脏的作答记录，不涉及越权。另外「已停用的题仍可提交」是刻意的：
     * 批次已经发给用户，此时拒绝会让他卡在一道永远答不了的题上。
     *
     * @param request 作答请求，不能为 null；batchId 与 questionId 必填且为正数；
     *                chosenLabels 允许带分隔符与小写，但不能含非法字符或重复标签
     * @return 判定结果，含正确答案、解析、错题本动作与累计统计；重复提交返回首次判定的快照
     *         （wrongBookAction 固定为 none）；永不为 null
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、
     *         标签非法 / 未选 / 不属于本题选项 / 单选传多个（A0401）、题目不存在（A0402）
     */
    QuizVerdictVO submitAnswer(QuizAnswerRequest request);

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * 跳过要单独落一条记录（result=跳过）而不是什么都不做：断点续答需要区分
     * 「这题我处理过了」与「还没轮到」，幂等也需要依据。但响应里只返回布尔——
     * 否则跳过按钮就成了看答案的捷径。
     *
     * 坑：刻意不推进错题本。跳过既不算答错也不算答对，误接进状态机会平白给错题本加一条记录、
     * 或推进连对进度，「跳过」就成了刷消解次数的漏洞。所选标签显式存 NULL 而不是空串，
     * 否则「跳过」与「提交了一个空答案」在数据上无法区分。
     *
     * @param request 跳过请求，不能为 null；batchId 与 questionId 必填且为正数
     * @return 恒为 true（重复跳过也返回 true）；失败只会以异常形式出现
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、
     *         题目不存在（A0402）、记录落库失败（B0002）
     */
    boolean skipQuestion(QuizSkipRequest request);

    /**
     * 学习概览（四个数字）
     *
     * 四个数字只需要三次查询：作答数与答对数由一条带 SUM(CASE WHEN) 的聚合查出
     * （两个数字来自同一行，天然一致），错题本的两个数由错题服务各查一次。
     *
     * 坑：正确率用 double 相除，必须先把分子转成 double，否则会先做整数除法再转换，
     * 结果只剩 0.0 与 1.0。accuracy 允许为 null（分母为 0 时），
     * 前端必须显示「--」而不是 0%，否则「没做过」会被读成「全错」。
     *
     * @return 概览对象，永不为 null；从未作答时两个计数为 0、accuracy 为 null；
     *         错题本的两个数字在没有任何条目时为 0
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）
     */
    QuizOverviewVO getOverview();

    /**
     * 校验当前登录用户是在队成员，并返回其账号 ID
     *
     * 教材 / 章节树由 QuizQuestionService 提供，但准入规则属于本服务。
     * 与其在 Controller 里复制一遍「查 studio_member 表」的逻辑（两份规则迟早漂移），
     * 不如暴露成公开方法，让所有 /quiz/** 入口共用同一个实现。
     *
     * 坑：每次调用都会查一次成员表且不缓存——成员状态可能在会话期间被管理员改动，
     * 缓存会让「刚被移出工作室的人还能继续刷题」。它只应在请求入口调用一次，
     * 把返回的 userId 往下传，不要放进循环。
     *
     * @return 当前登录账号 ID（用户 ID，不是成员档案 ID）
     * @throws BusinessException 未登录（A0201）或非在队成员（A0301）
     */
    long requireActiveMemberId();

    /**
     * 我的累计作答与答对（供判分后即时回填，避免前端再调一次概览接口）
     *
     * 两个数字总是一起用，拆成两个方法会让调用方查两次，而两次之间可能有新作答写入，
     * 于是两个数字与算出来的正确率对不上。用一条聚合让它们来自同一行。
     *
     * 坑：本方法不做成员校验，它是纯统计工具。调用方必须自己先确认 userId 属于当前登录用户，
     * 否则任何人都能查到别人的刷题量。跳过不计入作答，算进分母会让正确率虚低。
     *
     * @param userId 用户 ID；传错会统计到别人的数据，调用方负责传入当前登录用户
     * @return 长度 2 的数组：[累计作答数, 累计答对数]；没有作答记录时返回 {0, 0}，不会是 null
     */
    int[] countAnsweredAndCorrect(long userId);

    /**
     * 分页查某用户在某批次的作答记录（供测试与排查使用）
     *
     * 批次本身不落库，服务端唯一知道「这一轮做了哪些题」的地方就是作答记录表。
     * 按记录 ID 升序：作答记录是追加写的，ID 顺序即用户的操作顺序。
     *
     * 坑：返回的是实体而不是 VO，记录里含正确答案快照，因此不能直接暴露成 C 端接口。
     * 它也不做成员校验，userId 由调用方保证是当前登录用户。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @param batchId 批次 ID；传一个不存在的批次不会报错，只会得到空页
     * @return 分页结果，按记录 ID 升序；没有记录时 records 为空列表（不是 null）
     */
    Page<StudioQuizAnswer> listByBatch(long userId, long batchId);
}

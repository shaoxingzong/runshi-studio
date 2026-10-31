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
 * @author shaoshing
 */
public interface QuizPracticeService extends IService<StudioQuizAnswer> {

    /**
     * 开一批题目
     *
     * @param request 开批请求，不能为 null（为 null 抛 A0401）；mode 必填，0-顺序、1-错题重刷、2-随机；size 可为 null（用配置的默认题量），restart 为 null 时按 false 处理
     * @return 批次对象，含 batchId、模式、实际题量与题目列表（结构里没有答案字段）
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、模式非法或只给章节没给教材（A0401）、本章已刷完 / 错题本为空 / 范围内无题（A0402）
     */
    QuizBatchVO startBatch(QuizBatchRequest request);

    /**
     * 提交作答（幂等）
     *
     * @param request 作答请求，不能为 null；batchId 与 questionId 必填且为正数；chosenLabels 允许带分隔符与小写，但不能含非法字符或重复标签
     * @return 判定结果，含正确答案、解析、错题本动作与累计统计；重复提交返回首次判定的快照 （wrongBookAction 固定为 none）；永不为 null
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、标签非法 / 未选 / 不属于本题选项 / 单选传多个（A0401）、题目不存在（A0402）
     */
    QuizVerdictVO submitAnswer(QuizAnswerRequest request);

    /**
     * 跳过题目（不返回答案、不写错题本）
     *
     * @param request 跳过请求，不能为 null；batchId 与 questionId 必填且为正数
     * @return 恒为 true（重复跳过也返回 true）；失败只会以异常形式出现
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）、题目不存在（A0402）、记录落库失败（B0002）
     */
    boolean skipQuestion(QuizSkipRequest request);

    /**
     * 学习概览（四个数字）
     *
     * @return 概览对象，永不为 null；从未作答时两个计数为 0、accuracy 为 null；错题本的两个数字在没有任何条目时为 0
     * @throws BusinessException 未登录（A0201）、非在队成员（A0301）
     */
    QuizOverviewVO getOverview();

    /**
     * 校验当前登录用户是在队成员，并返回其账号 ID
     *
     * @return 当前登录账号 ID（用户 ID，不是成员档案 ID）
     * @throws BusinessException 未登录（A0201）或非在队成员（A0301）
     */
    long requireActiveMemberId();

    /**
     * 我的累计作答与答对（供判分后即时回填，避免前端再调一次概览接口）
     *
     * @param userId 用户 ID；传错会统计到别人的数据，调用方负责传入当前登录用户
     * @return 长度 2 的数组：[累计作答数, 累计答对数]；没有作答记录时返回 {0, 0}，不会是 null
     */
    int[] countAnsweredAndCorrect(long userId);

    /**
     * 分页查某用户在某批次的作答记录（供测试与排查使用）
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @param batchId 批次 ID；传一个不存在的批次不会报错，只会得到空页
     * @return 分页结果，按记录 ID 升序；没有记录时 records 为空列表（不是 null）
     */
    Page<StudioQuizAnswer> listByBatch(long userId, long batchId);
}

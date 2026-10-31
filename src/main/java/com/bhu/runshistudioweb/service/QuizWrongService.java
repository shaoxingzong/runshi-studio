package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.quiz.QuizWrongQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongDetailVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongItemVO;

/**
 * 刷题错题本服务
 *
 * @author shaoshing
 */
public interface QuizWrongService extends IService<StudioQuizWrong> {

    /**
     * 按一次作答结果更新错题本（状态机的唯一写入口）
     *
     * @param userId 用户 ID，准入由调用方校验，本方法不查成员表
     * @param questionId 题目 ID，必须是调用方刚刚判过分的题
     * @param resolveStreak 消解阈值（连续答对几次算掌握），小于 1 时按 1 处理
     */
    Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak);

    /**
     * 我的错题本分页
     *
     * @param userId 用户 ID，必须是当前登录用户；本方法不校验归属，调用方负责传自己的 ID
     * @param request 查询条件（教材名 / 章节名 / 分页参数），允许为 null
     * @return 分页结果，记录不含答案；没有待复习条目时返回空页（records 为空列表、total 为 0）
     */
    Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request);

    /**
     * 错题详情（含答案与解析）
     *
     * @param userId 当前登录用户 ID，用于归属校验；不是本人的条目一律按「不存在」处理
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛 A0401
     * @return 错题详情，含选项、正确答案、解析与上次所选；lastChosenLabels 没有答错记录时为 null
     * @throws BusinessException id 非法（A0401）、条目不存在或不属于当前用户（A0301）、题目已被删除（A0402）
     */
    QuizWrongDetailVO getMyWrongDetail(long userId, long id);

    /**
     * 手动「标记已掌握」
     *
     * @param userId 当前登录用户 ID，用于归属校验
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛 A0401
     * @return true 表示成功，包含「本来就已经消解」的情况；false 只可能是并发删除
     * @throws BusinessException id 非法（A0401）、条目不存在或不属于当前用户（A0301）
     */
    boolean resolveManually(long userId, long id);

    /**
     * 待复习错题数（学习概览用）
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 待复习条目数；没有错题时返回 0（不是 null）
     */
    int countPending(long userId);

    /**
     * 已消解错题数（学习概览用）
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 已消解条目数（按当前状态统计）；没有时返回 0（不是 null）
     */
    int countResolved(long userId);

    /**
     * 取待复习的题目 ID（错题重刷的候选池，按「最久没复习」排序）
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限；只传章节不传教材无法定位，调用方需先校验
     * @return 题目 ID 列表，按「最久没复习」升序；没有待复习题时返回空列表（不是 null）
     */
    java.util.List<Long> listPendingQuestionIds(long userId, String textbookName, String chapterName);

    record Outcome(String action, Integer remainingStreak) {
    }
}

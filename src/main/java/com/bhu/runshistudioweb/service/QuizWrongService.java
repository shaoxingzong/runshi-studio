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
 * author: shaoshing
 *
 * <p><b>本服务只被一个地方调用</b>：{@code QuizPracticeServiceImpl} 在判分之后
 * 调 {@link #applyOutcome}。这是刻意的——错题本的状态迁移必须只有<b>一个</b>写入口，
 * 否则「答对时该加 streak 还是直接消解」这类规则会散落在多处，
 * 迟早出现「答题页的判定说已消解、错题本页还显示待复习」的不一致。
 *
 * <p><b>状态机</b>（唯一的迁移表，改规则只改这里）：
 * <table border="1">
 *     <caption>applyOutcome</caption>
 *     <tr><th>事件</th><th>条目不存在</th><th>条目存在</th></tr>
 *     <tr><td>答错</td><td>插入 status=0, wrongCount=1, streak=0</td>
 *     <td>status→0, wrongCount+1, streak=0, resolvedAt=null</td></tr>
 *     <tr><td>答对</td><td>不动作</td>
 *     <td>待复习：streak+1；达阈值 → status=1, resolvedAt=now, resolveType=1</td></tr>
 *     <tr><td>跳过</td><td colspan="2"><b>完全不调用本方法</b>（「跳过不记错」）</td></tr>
 * </table>
 *
 * <p><b>题目被停用/删除时本表不动</b>：过滤发生在查询侧（子查询），
 * 这样停用是可逆的——重新启用后条目自动回来。
 *
 * <p>约定：参数不合法 / 越权时抛 {@code BusinessException}，不返回 null。
 */
public interface QuizWrongService extends IService<StudioQuizWrong> {

    /**
     * 按一次作答结果更新错题本（<b>状态机的唯一写入口</b>）
     *
     * @param userId         用户 ID
     * @param questionId     题目 ID
     * @param correct        本次是否答对
     * @param resolveStreak  消解阈值（连续答对几次算掌握）
     * @return 本次对错题本的影响，取值见 {@code QuizVerdictVO#wrongBookAction}
     *         （{@code added} / {@code reopened} / {@code progress} / {@code resolved} / {@code none}）
     */
    Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak);

    /**
     * 我的错题本分页
     *
     * <p>只返回<b>待复习</b>的条目，且<b>过滤掉已停用/已删除的题目</b>——
     * 列表与计数用同一个条件，否则会出现「计数 9 条、列表只有 7 条」。
     *
     * @param userId  用户 ID
     * @param request 查询条件（可按教材/章节筛选），允许为 null
     * @return 分页结果（<b>不含答案</b>）
     */
    Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request);

    /**
     * 错题详情（<b>含答案与解析</b>）
     *
     * <p>能进错题本的前提是「这道题你做错过」，做错过的人看到答案是应该的。
     * 但列表不带答案（见 {@link #listMyWrongByPage}）——浏览列表不该泄露答案。
     *
     * @param userId 用户 ID
     * @param id     错题本条目 ID（不是题目 ID）
     * @return 详情
     * @throws com.bhu.runshistudioweb.exception.BusinessException 条目不存在或不属于当前用户时抛出
     */
    QuizWrongDetailVO getMyWrongDetail(long userId, long id);

    /**
     * 手动「标记已掌握」
     *
     * <p><b>幂等</b>：已消解时直接返回 true，<b>不改 resolvedAt</b>——
     * 重复点击不该把「什么时候掌握的」这个时间点改掉。
     *
     * @param userId 用户 ID
     * @param id     错题本条目 ID
     * @return true 表示成功（含「本来就已经消解」的情况）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 条目不存在或不属于当前用户时抛出
     */
    boolean resolveManually(long userId, long id);

    /**
     * 待复习错题数（学习概览用）
     *
     * @param userId 用户 ID
     * @return 待复习条目数（已过滤停用/删除的题）
     */
    int countPending(long userId);

    /**
     * 已消解错题数（学习概览用）
     *
     * <p>口径是<b>当前状态</b>，不是历史累计：再答错会回到待复习，
     * 这个数字就应该减 1。否则「已消解」只增不减，对用户没有任何行动指引。
     *
     * @param userId 用户 ID
     * @return 已消解条目数（已过滤停用/删除的题）
     */
    int countResolved(long userId);

    /**
     * 取待复习的题目 ID（错题重刷的候选池，按「最久没复习」排序）
     *
     * @param userId       用户 ID
     * @param textbookName 教材名称，可为空（空表示不限）
     * @param chapterName  章节名称，可为空（空表示不限）
     * @return 题目 ID 列表；无错题时返回空列表
     */
    java.util.List<Long> listPendingQuestionIds(long userId, String textbookName, String chapterName);

    /**
     * 本次作答对错题本的影响
     *
     * <p>用 record 而不是返回字符串：调用方需要同时拿到「发生了什么」与
     * 「还差几次消解」（用于前端提示），只返回字符串会让后者无处安放。
     *
     * @param action          影响类型，直接对应 {@code QuizVerdictVO#wrongBookAction}
     * @param remainingStreak 还差几次消解（仅 {@code progress} 时非 null）
     */
    record Outcome(String action, Integer remainingStreak) {
    }
}

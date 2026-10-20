package com.bhu.runshistudioweb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.constant.QuizConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.QuizOptionCodec;
import com.bhu.runshistudioweb.mapper.StudioQuizAnswerMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizQuestionMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizWrongMapper;
import com.bhu.runshistudioweb.model.dto.quiz.QuizWrongQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import com.bhu.runshistudioweb.model.enums.QuizAnswerResultEnum;
import com.bhu.runshistudioweb.model.enums.QuizResolveTypeEnum;
import com.bhu.runshistudioweb.model.enums.QuizWrongStatusEnum;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongDetailVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongItemVO;
import com.bhu.runshistudioweb.service.QuizWrongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 刷题错题本服务实现
 *
 * author: shaoshing
 *
 * <p><b>本类的核心是 {@link #applyOutcome}，它是错题本状态机的唯一写入口</b>。
 * 所有「什么时候入本、什么时候消解、消解后再错怎么办」的规则都在那一个方法里，
 * 别处不允许直接改 {@code status} / {@code correct_streak}——
 * 规则一旦分散，就会出现「答题页说已消解、错题本页还显示待复习」的不一致，
 * 而这种不一致不报错，只能靠用户投诉发现。
 *
 * <p><b>为什么过滤停用题用子查询而不是清理条目</b>：
 * 停用是<b>可逆</b>的（管理员随时可能重新启用），如果停用时把条目删掉，
 * 重新启用后用户的错题记录就永久消失了。用 {@code inSql} 在查询侧过滤，
 * 重新启用后条目自动回来。代价是每条查询多一个子查询——但它走
 * {@code studio_quiz_question} 的主键索引，代价可以忽略。
 *
 * <p><b>列表与计数必须用同一个过滤条件</b>，否则会出现
 * 「概览显示 9 条待复习、列表只有 7 条」这种自相矛盾的界面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizWrongServiceImpl extends ServiceImpl<StudioQuizWrongMapper, StudioQuizWrong>
        implements QuizWrongService {

    /** 错题本条目状态：待复习 */
    private static final int STATUS_PENDING = QuizWrongStatusEnum.PENDING.getValue();

    /** 错题本条目状态：已消解 */
    private static final int STATUS_RESOLVED = QuizWrongStatusEnum.RESOLVED.getValue();

    /**
     * 「题目可见」的子查询条件
     *
     * <p>停用（{@code status=1}）与删除（{@code deleted_at≠0}）的题都不出现在错题本里。
     * 抽成常量是为了让<b>列表与计数用同一个字符串</b>——
     * 两处各写一份，改一处漏一处就会出现「计数 9、列表 7」。
     */
    private static final String VISIBLE_QUESTION_SQL =
            "SELECT id FROM studio_quiz_question WHERE deleted_at = 0 AND status = 0";

    private final StudioQuizQuestionMapper studioQuizQuestionMapper;

    private final StudioQuizAnswerMapper studioQuizAnswerMapper;

    private final QuizOptionCodec quizOptionCodec;

    // ==================== 状态机 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak) {
        StudioQuizWrong existing = findByUserAndQuestion(userId, questionId);

        if (!correct) {
            return handleWrong(userId, questionId, existing);
        }
        return handleCorrect(existing, resolveStreak);
    }

    /**
     * 答错：入本或累加
     *
     * <p>「首次入本」与「再错」的区别只影响前端提示文案（added / reopened），
     * 落库规则完全一致——都是「回到待复习、次数 +1、连对归零」。
     * 已消解的条目再错会清空 {@code resolvedAt / resolveType}，
     * 于是「已消解 → 待复习」这个迁移不需要任何额外代码。
     */
    private Outcome handleWrong(long userId, long questionId, StudioQuizWrong existing) {
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            StudioQuizWrong wrong = new StudioQuizWrong();
            wrong.setUserId(userId);
            wrong.setQuestionId(questionId);
            wrong.setStatus(STATUS_PENDING);
            wrong.setWrongCount(1);
            wrong.setCorrectStreak(0);
            wrong.setLastWrongAt(now);
            boolean saved = this.save(wrong);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录错题失败");
            return new Outcome("added", null);
        }

        boolean wasResolved = Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus());
        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        update.setStatus(STATUS_PENDING);
        update.setWrongCount(safeInt(existing.getWrongCount()) + 1);
        update.setCorrectStreak(0);
        update.setLastWrongAt(now);
        this.updateById(update);

        // resolvedAt / resolveType 要清空，但 updateById 会跳过 null 字段，
        // 所以必须用 wrapper 显式 set（与题目「清空解析」是同一个坑）
        if (wasResolved) {
            LambdaUpdateWrapperHolder.clearResolved(this, existing.getId());
        }
        return new Outcome(wasResolved ? "reopened" : "added", null);
    }

    /**
     * 答对：推进连对进度，达阈值即消解
     *
     * <p><b>不在错题本里就什么都不做</b>：一道从来没做错过的题答对了，
     * 没有任何「状态」需要更新。给它建一条已消解的条目是错的——
     * 那会让「已消解」变成「做对过的题数」，与它的语义（曾经错过、现在掌握了）不符。
     */
    private Outcome handleCorrect(StudioQuizWrong existing, int resolveStreak) {
        if (existing == null) {
            return new Outcome("none", null);
        }
        if (Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus())) {
            // 已消解的题再答对：不需要任何动作。不推进 streak，
            // 也不刷新 resolvedAt（否则「什么时候掌握的」会一直往后漂）
            return new Outcome("none", null);
        }

        int threshold = Math.max(1, resolveStreak);
        int newStreak = safeInt(existing.getCorrectStreak()) + 1;

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        update.setCorrectStreak(newStreak);
        if (newStreak >= threshold) {
            update.setStatus(STATUS_RESOLVED);
            update.setResolvedAt(LocalDateTime.now());
            update.setResolveType(QuizResolveTypeEnum.AUTO.getValue());
            this.updateById(update);
            return new Outcome("resolved", 0);
        }
        this.updateById(update);
        return new Outcome("progress", threshold - newStreak);
    }

    // ==================== 查询 ====================

    @Override
    public Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request) {
        QuizWrongQueryRequest query = request == null ? new QuizWrongQueryRequest() : request;

        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 过滤停用/删除的题：与 countPending 用同一个条件，否则计数与列表对不上
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)
                // 最久没复习的先来：错题本的价值在于「把忘掉的捡回来」，
                // 而不是「反复刷最近刚错的那道」
                .orderByAsc(StudioQuizWrong::getLastWrongAt)
                .orderByAsc(StudioQuizWrong::getId);

        // 按教材/章节筛选：先查出范围内题目 ID，再用 IN 过滤。
        // 不能 JOIN——本项目零手写 SQL，Wrapper 表达不了 JOIN，
        // 而两次查询在「错题量几十到几百」的量级下完全够用
        List<Long> scopedQuestionIds = null;
        if (query.getTextbookName() != null || query.getChapterName() != null) {
            scopedQuestionIds = questionIdsOf(query.getTextbookName(), query.getChapterName());
            if (scopedQuestionIds.isEmpty()) {
                return new Page<>(1, QuizConstant.DEFAULT_PAGE_SIZE, 0);
            }
            wrapper.in(StudioQuizWrong::getQuestionId, scopedQuestionIds);
        }

        Page<StudioQuizWrong> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);
        List<QuizWrongItemVO> records = toItemVOs(entityPage.getRecords());

        Page<QuizWrongItemVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public QuizWrongDetailVO getMyWrongDetail(long userId, long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        // 不存在与不属于本人返回同一个错误：不暴露「这个 id 存在但不是你的」
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(wrong.getQuestionId());
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目已被删除");

        QuizWrongDetailVO detail = new QuizWrongDetailVO();
        fillItem(detail, wrong, question);
        // 详情才带答案：能进错题本的前提是「你做错过」
        detail.setOptions(quizOptionCodec.decode(question.getOptions()));
        detail.setCorrectLabels(question.getCorrectLabels());
        detail.setAnalysis(question.getAnalysis());
        detail.setLastChosenLabels(lastWrongChosen(userId, wrong.getQuestionId()));
        return detail;
    }

    @Override
    public boolean resolveManually(long userId, long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        // 幂等：已消解时直接返回，不改 resolvedAt——
        // 重复点击不该把「什么时候掌握的」这个时间点改掉
        if (Integer.valueOf(STATUS_RESOLVED).equals(wrong.getStatus())) {
            return true;
        }

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(id);
        update.setStatus(STATUS_RESOLVED);
        update.setResolvedAt(LocalDateTime.now());
        update.setResolveType(QuizResolveTypeEnum.MANUAL.getValue());
        return this.updateById(update);
    }

    @Override
    public int countPending(long userId) {
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 与列表用同一个过滤条件：否则会出现「计数 9、列表 7」
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    @Override
    public int countResolved(long userId) {
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_RESOLVED)
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    @Override
    public List<Long> listPendingQuestionIds(long userId, String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizWrong::getQuestionId)
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)
                // 最久没复习的先来（与列表同序，用户感知一致）
                .orderByAsc(StudioQuizWrong::getLastWrongAt)
                .orderByAsc(StudioQuizWrong::getId);

        List<StudioQuizWrong> entries = this.list(wrapper);
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        if (textbookName == null && chapterName == null) {
            return questionIds;
        }
        // 范围过滤：保留原顺序（最久没复习优先），只剔掉不在范围内的
        List<Long> scoped = questionIdsOf(textbookName, chapterName);
        return questionIds.stream().filter(scoped::contains).toList();
    }

    // ==================== 私有 ====================

    /**
     * 按用户 + 题目查错题条目
     *
     * @return 条目；不存在时返回 null
     */
    private StudioQuizWrong findByUserAndQuestion(long userId, long questionId) {
        return this.getOne(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getQuestionId, questionId));
    }

    /**
     * 取范围内（教材/章节，均可空）的题目 ID
     *
     * @return 题目 ID 列表；无条件时返回空列表（调用方需先判断「是否真的需要过滤」）
     */
    private List<Long> questionIdsOf(String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizQuestion::getId)
                .eq(textbookName != null, StudioQuizQuestion::getTextbookName, textbookName)
                .eq(chapterName != null, StudioQuizQuestion::getChapterName, chapterName);
        return studioQuizQuestionMapper.selectList(wrapper).stream()
                .map(StudioQuizQuestion::getId)
                .collect(Collectors.toList());
    }

    /**
     * 该用户对某题最近一次答错时所选的标签
     *
     * <p>用于错题回顾时对照「你上次选了什么」——只显示正确答案的话，
     * 用户不知道自己当时错在哪。
     *
     * @return 所选标签；没有答错记录时返回 null
     */
    private String lastWrongChosen(long userId, long questionId) {
        List<StudioQuizAnswer> answers = studioQuizAnswerMapper.selectList(
                new LambdaQueryWrapper<StudioQuizAnswer>()
                        .eq(StudioQuizAnswer::getUserId, userId)
                        .eq(StudioQuizAnswer::getQuestionId, questionId)
                        .eq(StudioQuizAnswer::getResult, QuizAnswerResultEnum.WRONG.getValue())
                        .orderByDesc(StudioQuizAnswer::getId)
                        .last("LIMIT 1"));
        if (answers.isEmpty()) {
            return null;
        }
        return answers.get(0).getChosenLabels();
    }

    /**
     * 错题条目批量转 VO（题目信息一次查完，避免 N+1）
     */
    private List<QuizWrongItemVO> toItemVOs(List<StudioQuizWrong> entries) {
        List<QuizWrongItemVO> result = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        Map<Long, StudioQuizQuestion> questionMap = studioQuizQuestionMapper.selectByIds(questionIds).stream()
                .collect(Collectors.toMap(StudioQuizQuestion::getId, q -> q, (a, b) -> a));

        for (StudioQuizWrong entry : entries) {
            StudioQuizQuestion question = questionMap.get(entry.getQuestionId());
            if (question == null) {
                // 题目已删除：条目本该在删除时被清理，这里是防御性跳过
                continue;
            }
            QuizWrongItemVO vo = new QuizWrongItemVO();
            fillItem(vo, entry, question);
            result.add(vo);
        }
        return result;
    }

    /**
     * 填充错题条目与题目的公共字段（列表与详情共用）
     */
    private void fillItem(QuizWrongItemVO vo, StudioQuizWrong entry, StudioQuizQuestion question) {
        vo.setId(entry.getId());
        vo.setQuestionId(entry.getQuestionId());
        vo.setType(question.getType());
        vo.setStem(question.getStem());
        vo.setTextbookName(question.getTextbookName());
        vo.setChapterName(question.getChapterName());
        vo.setStatus(entry.getStatus());
        vo.setWrongCount(entry.getWrongCount());
        vo.setCorrectStreak(entry.getCorrectStreak());
        vo.setLastWrongAt(entry.getLastWrongAt());
        vo.setResolvedAt(entry.getResolvedAt());
        vo.setResolveType(entry.getResolveType());
    }

    /**
     * 分页参数兜底纠正（不抛异常，与其它模块一致）
     */
    private Page<StudioQuizWrong> newPageWithDefaults(Long current, Long pageSize) {
        long page = current == null || current < 1 ? 1L : current;
        long size = pageSize == null || pageSize < 1 ? QuizConstant.DEFAULT_PAGE_SIZE : pageSize;
        return new Page<>(page, Math.min(size, QuizConstant.MAX_PAGE_SIZE));
    }

    /** 计数为 null 时按 0 处理（老数据在加列之前可能没有值） */
    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 「清空消解信息」的小工具
     *
     * <p>抽成内部类只是为了避免在 {@link #handleWrong} 里塞一段
     * 与本方法职责无关的 wrapper 构造代码；它不做任何业务判断。
     */
    private static final class LambdaUpdateWrapperHolder {

        private static void clearResolved(QuizWrongServiceImpl service, Long id) {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StudioQuizWrong> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            wrapper.eq(StudioQuizWrong::getId, id)
                    .set(StudioQuizWrong::getResolvedAt, null)
                    .set(StudioQuizWrong::getResolveType, null);
            service.baseMapper.update(null, wrapper);
        }

        private LambdaUpdateWrapperHolder() {
        }
    }
}

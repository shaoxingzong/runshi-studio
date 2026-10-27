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
 * applyOutcome 是错题本状态机的唯一写入口，「什么时候入本、什么时候消解、消解后再错怎么办」
 * 都在那里，别处不允许直接改 status / correct_streak：规则一旦分散，就会出现
 * 「答题页说已消解、错题本页还显示待复习」的不一致，而这种不一致不报错，只能靠用户投诉发现。
 *
 * 停用题用 inSql 在查询侧过滤而不是清理条目：停用是可逆的，删掉条目后重新启用，
 * 用户的错题记录就永久消失了。列表与计数必须用同一个过滤条件，否则会出现
 * 「概览显示 9 条待复习、列表只有 7 条」这种自相矛盾的界面。
 *
 * @author shaoshing
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
     * 停用（status=1）与删除（deleted_at≠0）的题都不出现在错题本里。
     * 抽成常量是为了让列表与计数共用同一个字符串——两处各写一份，改一处漏一处就会出现「计数 9、列表 7」。
     */
    private static final String VISIBLE_QUESTION_SQL =
            "SELECT id FROM studio_quiz_question WHERE deleted_at = 0 AND status = 0";

    private final StudioQuizQuestionMapper studioQuizQuestionMapper;

    private final StudioQuizAnswerMapper studioQuizAnswerMapper;

    private final QuizOptionCodec quizOptionCodec;

    // ==================== 状态机 ====================

    /**
     * 作答后更新错题本状态机（唯一的状态迁移入口）
     *
     * 答错加入待复习、答对累加连对、连对达到阈值才消解、已消解的题再答错回到待复习。
     * 这些迁移只能有一个写入口，否则「提交作答」与「手动标记已掌握」各写一套规则，
     * 迟早出现答题页与错题本页状态不一致，且不报错。
     *
     * 坑：本方法带事务但没有行锁，并发提交可能两个线程都查到「条目不存在」然后都去 insert，
     * 撞唯一键的会回滚。真正拦住重复提交的是作答表上的唯一键。
     *
     * @param correct 本次是否答对；答错会累加错误次数并把连对清零
     * @param resolveStreak 消解阈值，小于 1 时按 1 处理
     * @return 本次影响，取值 added / reopened / progress / resolved / none；永不为 null
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak) {
        // ① 先读后写：必须知道条目此前是否存在，才能决定 insert 还是 update
        StudioQuizWrong existing = findByUserAndQuestion(userId, questionId);

        // ② 答错与答对是完全不同的两条支路，分支放在这里而不是各自方法里判断，
        //    是为了让「读一次」这件事只发生一次
        if (!correct) {
            return handleWrong(userId, questionId, existing);
        }
        // ③ 答对只推进连对进度；这里不再查一次库，复用①的结果
        return handleCorrect(existing, resolveStreak);
    }

    /**
     * 答错：首次入本，或把已有条目打回待复习并累加次数
     *
     * 「首次入本」与「再错一次」的落库规则刻意写成一致的：都回到待复习、错误次数 +1、连对归零。
     * 这样「已消解 → 待复习」不需要额外代码，区别只在返回给前端的动作类型（added 还是 reopened）。
     *
     * 坑：清空 resolvedAt / resolveType 不能用实体更新，updateById 会跳过 null 字段，
     * 必须走 wrapper 显式 set。错误次数是「读出来 +1 再写回」，并发时会丢计数，
     * 但作答表的唯一键已经保证同一批同一题只提交一次。
     *
     * @param existing 该用户对该题已有的错题条目；为 null 表示首次做错
     * @return added（首次或未消解过）或 reopened（曾经消解过）
     */
    private Outcome handleWrong(long userId, long questionId, StudioQuizWrong existing) {
        // 时间只取一次：两处写入共用同一个时刻，避免同一行记录出现两个不同的「错误时间」
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            StudioQuizWrong wrong = new StudioQuizWrong();
            wrong.setUserId(userId);
            wrong.setQuestionId(questionId);
            // ① 新条目一定是待复习：做错过才进本，进来就等着复习
            wrong.setStatus(STATUS_PENDING);
            wrong.setWrongCount(1);
            wrong.setCorrectStreak(0);
            wrong.setLastWrongAt(now);
            boolean saved = this.save(wrong);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录错题失败");
            return new Outcome("added", null);
        }

        // ② 必须在更新之前判断：更新之后 status 就是待复习了，看不出原来是否已消解
        boolean wasResolved = Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus());
        // ③ 新建实体 + setId 做部分更新：updateById 只写非 null 字段，不会误改其它列
        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        // 无条件写回待复习：这一行同时完成了「首次加入」与「已消解打回」两种迁移
        update.setStatus(STATUS_PENDING);
        update.setWrongCount(safeInt(existing.getWrongCount()) + 1);
        // 答错即清零连对：连对的前提是「连续答对」，中间断了一次就不能接着算
        update.setCorrectStreak(0);
        update.setLastWrongAt(now);
        this.updateById(update);

        // ④ resolvedAt / resolveType 要清空，但 updateById 会跳过 null 字段，
        // 所以必须用 wrapper 显式 set（与题目「清空解析」是同一个坑）
        if (wasResolved) {
            LambdaUpdateWrapperHolder.clearResolved(this, existing.getId());
        }
        // 已消解过的条目再次答错，用 reopened 让前端提示「这道题又回来了」
        return new Outcome(wasResolved ? "reopened" : "added", null);
    }

    /**
     * 答对：推进连对进度，达到阈值就消解
     *
     * 不在错题本里就什么都不做：给从没做错过的题建一条「已消解」记录，会让「已消解」变成
     * 「做对过的题数」，学习概览的数字随之失去意义。已消解的也什么都不做，
     * 否则「什么时候掌握的」这个时间点会随每次答对一直往后漂。
     *
     * 坑：阈值先夹到至少 1，配置成 0 会让第一次答对就消解；连对必须先 +1 再与阈值比较。
     * 返回的 remainingStreak 在 resolved 时是 0 而不是 null，前端要看 action 而不是看数字。
     *
     * @param existing 该用户对该题已有的错题条目；为 null 表示这道题没做错过
     * @param resolveStreak 消解阈值，小于 1 时按 1 处理
     * @return none（不在本里或已消解）、progress（还差 remainingStreak 次）、resolved（本次消解）
     */
    private Outcome handleCorrect(StudioQuizWrong existing, int resolveStreak) {
        // ① 做对的题不进错题本：没有条目就没有任何状态可更新
        if (existing == null) {
            return new Outcome("none", null);
        }
        if (Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus())) {
            // ② 已消解的题再答对：不需要任何动作。不推进 streak，
            // 也不刷新 resolvedAt（否则「什么时候掌握的」会一直往后漂）
            return new Outcome("none", null);
        }

        // ③ 阈值夹到至少 1，防配置写成 0 导致「一次答对就消解」
        int threshold = Math.max(1, resolveStreak);
        int newStreak = safeInt(existing.getCorrectStreak()) + 1;

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        update.setCorrectStreak(newStreak);
        // ④ 先加再比：否则「差最后一次」的答对不会触发消解
        if (newStreak >= threshold) {
            // ⑤ 三个字段同一次 update 写入，避免「已消解却没有消解时间」的中间状态
            update.setStatus(STATUS_RESOLVED);
            update.setResolvedAt(LocalDateTime.now());
            // 自动消解与手动标记用不同的 resolveType，便于事后区分是谁判定掌握的
            update.setResolveType(QuizResolveTypeEnum.AUTO.getValue());
            this.updateById(update);
            return new Outcome("resolved", 0);
        }
        // ⑥ 未达阈值只推进连对，剩余次数交给前端提示
        this.updateById(update);
        return new Outcome("progress", threshold - newStreak);
    }

    // ==================== 查询 ====================

    /**
     * 我的错题本分页（只列待复习，不含答案）
     *
     * 列表与计数必须用同一套过滤条件，否则会出现「概览 9 条、列表 7 条」这种不报错、
     * 只让用户觉得数据不可信的界面，因此「可见题目」被抽成常量供三处共用。
     * 按教材/章节筛选时先查题目 ID 再用 IN 过滤而不是 JOIN：Wrapper 表达不了 JOIN，
     * 错题量在几十到几百的量级，两次查询够用且各自能走索引。
     *
     * 坑：范围筛选查出空 ID 列表时要直接返回空页，否则会带着空 IN 条件去查库（有些数据库直接报错）。
     *
     * @param userId 用户 ID，本方法不校验归属，调用方负责传入自己的 ID
     * @param request 查询条件（教材名 / 章节名 / 分页参数），允许为 null
     * @return 分页结果；没有待复习条目时返回空页（records 为空列表，total 为 0）
     */
    @Override
    public Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request) {
        // ① 请求为 null 时兜一个空对象，下面就不必到处判空
        QuizWrongQueryRequest query = request == null ? new QuizWrongQueryRequest() : request;

        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioQuizWrong::getUserId, userId)
                // ② 只列待复习：已消解的条目留在库里是为了保留「什么时候掌握的」，但不再展示
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 过滤停用/删除的题：与 countPending 用同一个条件，否则计数与列表对不上
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)
                // 最久没复习的先来：错题本的价值在于「把忘掉的捡回来」，
                // 而不是「反复刷最近刚错的那道」
                .orderByAsc(StudioQuizWrong::getLastWrongAt)
                // 同一时刻入本的多条按 id 兜底排序，保证分页结果稳定（否则翻页可能重复或漏条）
                .orderByAsc(StudioQuizWrong::getId);

        // 按教材/章节筛选：先查出范围内题目 ID，再用 IN 过滤。
        // 不能 JOIN——本项目零手写 SQL，Wrapper 表达不了 JOIN，
        // 而两次查询在「错题量几十到几百」的量级下完全够用
        List<Long> scopedQuestionIds = null;
        if (query.getTextbookName() != null || query.getChapterName() != null) {
            scopedQuestionIds = questionIdsOf(query.getTextbookName(), query.getChapterName());
            // ③ 范围内没有题：直接返回空页，避免带着空 IN 条件去查库
            if (scopedQuestionIds.isEmpty()) {
                return new Page<>(1, QuizConstant.DEFAULT_PAGE_SIZE, 0);
            }
            wrapper.in(StudioQuizWrong::getQuestionId, scopedQuestionIds);
        }

        // ④ 分页参数走兜底纠正：页码传错不该让整个查询失败
        Page<StudioQuizWrong> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);
        // ⑤ 题目信息一次批量查完，避免 N+1
        List<QuizWrongItemVO> records = toItemVOs(entityPage.getRecords());

        // ⑥ current / size / total 必须一并带过去，否则前端分页控件会显示成空
        Page<QuizWrongItemVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /**
     * 错题详情（含题干、选项、正确答案、解析、上次所选）
     *
     * 这里可以下发答案：能进错题本的前提是这道题你做错过，藏着反而让人无法复盘。
     * 列表接口则不带答案——浏览列表时没有「复盘这一题」的意图，泄露答案就成了作弊捷径。
     * 「不存在」与「不属于本人」返回同一个错误，否则别人能靠错误码枚举出哪些 id 真实存在。
     *
     * 坑：正确答案取题目表里的当前值而不是作答记录里的快照，管理员改过答案后两者可能对不上——
     * 详情页要回答的是「正确答案是什么」，不是「当时系统认为的正确答案是什么」。
     *
     * @param userId 当前登录用户 ID，用于归属校验；不是本人的条目一律按「不存在」处理
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛参数错误
     * @return 错题详情，含正确答案与解析
     * @throws BusinessException id 非法抛 A0401；条目不存在或不属于当前用户抛 A0301；题目已删除抛 A0402
     */
    @Override
    public QuizWrongDetailVO getMyWrongDetail(long userId, long id) {
        // ① 负数与 0 不可能存在，早点拒绝省一次查询
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        // ② 不存在与不属于本人返回同一个错误：不暴露「这个 id 存在但不是你的」
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(wrong.getQuestionId());
        // ③ 题目已被逻辑删除：这里给「已删除」比给「无权访问」更贴近事实
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目已被删除");

        QuizWrongDetailVO detail = new QuizWrongDetailVO();
        // ④ 公共字段（题干、教材章节、次数、时间）先由 fillItem 统一填充
        fillItem(detail, wrong, question);
        // 详情才带答案：能进错题本的前提是「你做错过」
        detail.setOptions(quizOptionCodec.decode(question.getOptions()));
        detail.setCorrectLabels(question.getCorrectLabels());
        detail.setAnalysis(question.getAnalysis());
        // ⑤ 「上次所选」存在作答表里，错题条目只记状态，所以必须单独查
        detail.setLastChosenLabels(lastWrongChosen(userId, wrong.getQuestionId()));
        return detail;
    }

    /**
     * 手动标记「已掌握」（直接把条目置为已消解）
     *
     * 自动消解要求连续答对若干次，但用户可能已经通过别的材料掌握了这道题，手动入口跳过连对计数。
     * 已消解时直接返回成功且不刷新 resolvedAt：重复点击不该把「什么时候掌握的」这个时间点改掉，
     * 否则错题本的时间线就失去了意义。
     *
     * @param userId 当前登录用户 ID，用于归属校验
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛参数错误
     * @return true 表示成功，包含「本来就已经消解」的情况
     * @throws BusinessException id 非法抛 A0401；条目不存在或不属于当前用户抛 A0301
     */
    @Override
    public boolean resolveManually(long userId, long id) {
        // ① 与详情接口同一套入参校验
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        // ② 归属校验必须在写之前：不校验就能改别人的错题本
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        // ③ 幂等：已消解时直接返回，不改 resolvedAt——
        // 重复点击不该把「什么时候掌握的」这个时间点改掉
        if (Integer.valueOf(STATUS_RESOLVED).equals(wrong.getStatus())) {
            return true;
        }

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(id);
        update.setStatus(STATUS_RESOLVED);
        update.setResolvedAt(LocalDateTime.now());
        // ④ 手动与自动用不同的 resolveType，便于事后区分是谁判定掌握的
        update.setResolveType(QuizResolveTypeEnum.MANUAL.getValue());
        return this.updateById(update);
    }

    /**
     * 待复习错题数（学习概览用）
     *
     * 必须与错题本列表的条数完全一致，否则用户会看到「概览说 9 条、点进去只有 7 条」。
     * 因此过滤条件与 listMyWrongByPage 完全相同，连「可见题目」的子查询都是同一个常量。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 待复习条目数；没有错题时返回 0
     */
    @Override
    public int countPending(long userId) {
        // ① 用户 + 状态 + 题目可见性，三个条件与列表接口完全一致
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 与列表用同一个过滤条件：否则会出现「计数 9、列表 7」
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    /**
     * 已消解错题数（学习概览用）
     *
     * 口径是「当前状态」而不是「历史累计」：已消解的题再答错会回到待复习，这个数字就该减 1。
     * 按累计算只增不减，对用户没有任何行动指引。题目停用或删除时也不计入。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 已消解条目数（按当前状态统计）；没有时返回 0
     */
    @Override
    public int countResolved(long userId) {
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_RESOLVED)
                // 与待复习数同一套可见性口径，两个数字才能相加着看
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    /**
     * 待复习错题的题目 ID（错题重刷模式的开批来源）
     *
     * 只认 status=待复习，并过滤掉停用与删除的题目，否则会出一批点进去没有内容的题。
     * 按 lastWrongAt 升序：错题本的价值在于隔一段时间再考一次，总先出最近刚错的等于没复习。
     *
     * 坑：有范围条件时先查范围内题目 ID 再用 filter 保留原顺序，不能反过来取交集，那会丢掉排序；
     * filter 用 List.contains 是 O(n²)，靠候选量封顶（几十条）扛住，扩到上千条要改成 HashSet。
     *
     * @param textbookName 教材名称，为 null 表示不限；空串由调用方归一成 null
     * @param chapterName 章节名称，为 null 表示不限；只传章节无法定位，调用方需先校验
     * @return 题目 ID 列表，按「最久没复习」升序；没有待复习题时返回空列表
     */
    @Override
    public List<Long> listPendingQuestionIds(long userId, String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizWrong::getQuestionId)
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 停用/删除的题不进候选池：否则会抽出一批点进去没有内容的题
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)
                // 最久没复习的先来（与列表同序，用户感知一致）
                .orderByAsc(StudioQuizWrong::getLastWrongAt)
                .orderByAsc(StudioQuizWrong::getId);

        List<StudioQuizWrong> entries = this.list(wrapper);
        // ② 空结果直接给空列表，调用方只需判 isEmpty
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        // ③ 不限范围：省掉一次「查范围内题目」的查询
        if (textbookName == null && chapterName == null) {
            return questionIds;
        }
        // ④ 范围过滤：保留原顺序（最久没复习优先），只剔掉不在范围内的
        List<Long> scoped = questionIdsOf(textbookName, chapterName);
        return questionIds.stream().filter(scoped::contains).toList();
    }

    // ==================== 私有 ====================

    /**
     * 按「用户 + 题目」查错题条目
     *
     * 用 getOne 而不是 list 再取第一条：唯一键被破坏时直接抛异常，比悄悄取第一条更能暴露问题。
     *
     * @param userId 用户 ID
     * @param questionId 题目 ID
     * @return 错题条目；不存在时返回 null 表示「这道题没进过错题本」，这是正常状态，调用方必须判空
     */
    private StudioQuizWrong findByUserAndQuestion(long userId, long questionId) {
        return this.getOne(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getQuestionId, questionId));
    }

    /**
     * 取范围内（教材 / 章节，均可空）的题目 ID
     *
     * 错题表里只有 question_id，教材章节是题目表上的名字列，因此「按章节筛错题」只能先查出题目 ID
     * 再拿去过滤。条件用「非 null 才拼接」而不是 {@code column = ? OR ? IS NULL}，后者会让索引失效。
     *
     * 坑：两个参数都为 null 时会返回全部题目 ID，看起来像「不限范围」，实际是一次全表扫描，
     * 调用方要先判断有没有筛选条件。
     *
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限
     * @return 题目 ID 列表；范围内没有题时返回空列表
     */
    private List<Long> questionIdsOf(String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizQuestion::getId)
                // 非 null 才拼条件：用「OR IS NULL」的写法会让索引失效
                .eq(textbookName != null, StudioQuizQuestion::getTextbookName, textbookName)
                .eq(chapterName != null, StudioQuizQuestion::getChapterName, chapterName);
        return studioQuizQuestionMapper.selectList(wrapper).stream()
                .map(StudioQuizQuestion::getId)
                .collect(Collectors.toList());
    }

    /**
     * 该用户对某题最近一次答错时所选的标签
     *
     * 只显示正确答案不够——用户看到「正确答案 ABD」却不知道自己选了什么，无法定位错在哪。
     * 只取最近一次答错而不是最近一次作答：答对的那次不构成复盘素材，且答对时条目可能已消解。
     *
     * 坑：{@code last("LIMIT 1")} 是字符串拼接，只允许拼常量，拼用户输入就是 SQL 注入。
     *
     * @param userId 用户 ID
     * @param questionId 题目 ID
     * @return 最近一次答错时所选的标签串；没有答错记录时返回 null（前端需判空）
     */
    private String lastWrongChosen(long userId, long questionId) {
        List<StudioQuizAnswer> answers = studioQuizAnswerMapper.selectList(
                new LambdaQueryWrapper<StudioQuizAnswer>()
                        .eq(StudioQuizAnswer::getUserId, userId)
                        .eq(StudioQuizAnswer::getQuestionId, questionId)
                        // 只要答错的那次：答对的不构成复盘素材
                        .eq(StudioQuizAnswer::getResult, QuizAnswerResultEnum.WRONG.getValue())
                        .orderByDesc(StudioQuizAnswer::getId)
                        // 拼常量 LIMIT，不拼接任何用户输入
                        .last("LIMIT 1"));
        // ③ 没有答错记录：返回 null，前端据此决定是否渲染「上次所选」
        if (answers.isEmpty()) {
            return null;
        }
        return answers.get(0).getChosenLabels();
    }

    /**
     * 错题条目批量转 VO（题目信息一次查完，避免 N+1）
     *
     * 逐条查题目就是 1 次分页 + 20 次题目查询。这里先收集 questionId 一次 selectByIds 捞回放进 Map，
     * 查询次数恒为 2，与页大小无关。
     *
     * 坑：题目缺失的条目会被跳过，返回列表可能短于入参，不能假设「第 i 条 VO 对应第 i 条实体」；
     * toMap 必须带合并函数，否则重复 id 会抛 IllegalStateException。
     *
     * @param entries 错题条目列表，可为 null
     * @return 错题条目 VO 列表；入参为空时返回空列表
     */
    private List<QuizWrongItemVO> toItemVOs(List<StudioQuizWrong> entries) {
        List<QuizWrongItemVO> result = new ArrayList<>();
        // ① 空页直接返回，不必走查询
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        // ② 一次查完：查询次数恒为 2，与页大小无关
        Map<Long, StudioQuizQuestion> questionMap = studioQuizQuestionMapper.selectByIds(questionIds).stream()
                .collect(Collectors.toMap(StudioQuizQuestion::getId, q -> q, (a, b) -> a));

        for (StudioQuizWrong entry : entries) {
            StudioQuizQuestion question = questionMap.get(entry.getQuestionId());
            if (question == null) {
                // ③ 题目已删除：条目本该在删除时被清理，这里是防御性跳过
                continue;
            }
            QuizWrongItemVO vo = new QuizWrongItemVO();
            // ④ 公共字段与详情共用 fillItem，避免两处赋值逐渐分叉
            fillItem(vo, entry, question);
            result.add(vo);
        }
        return result;
    }

    /**
     * 填充错题条目与题目的公共字段（列表与详情共用）
     *
     * 列表与详情有一大半字段重合，各写一份赋值，改字段时漏掉一处就会出现「列表有、详情没有」
     * 这种只有用户能发现的差异。题目上的字段取的是最新值，不是用户答题时的快照。
     *
     * 坑：入参是 QuizWrongDetailVO 的父类型，两个 VO 字段一旦分叉，要把分叉的挪出去，
     * 而不是在这里加 if 判断类型。
     *
     * @param vo 待填充的 VO（就地修改），列表项与详情共用同一个父类型
     * @param entry 错题条目实体，提供状态、次数与时间
     * @param question 题目实体，提供题型、题干与教材章节
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
     * 分页参数兜底纠正（不抛异常）
     *
     * 非法值一律换成默认值、超限值压到上限：不设上限的话，前端传 pageSize=100000 就是一次全表拉取。
     * 只处理「参数本身是否合理」，页码超出总页数属于正常业务结果，返回空页即可。
     *
     * @param current 请求的页码，可为 null
     * @param pageSize 请求的页大小，可为 null
     * @return 纠正后的分页对象，页码至少为 1，页大小落在 [1, MAX_PAGE_SIZE]
     */
    private Page<StudioQuizWrong> newPageWithDefaults(Long current, Long pageSize) {
        // ① 负数页码会算出负偏移量，必须纠正成第 1 页
        long page = current == null || current < 1 ? 1L : current;
        // ② 非法值用默认值，超限值压到上限，防止一次拉全表
        long size = pageSize == null || pageSize < 1 ? QuizConstant.DEFAULT_PAGE_SIZE : pageSize;
        return new Page<>(page, Math.min(size, QuizConstant.MAX_PAGE_SIZE));
    }

    /**
     * 计数列取值兜底：null 按 0 处理
     *
     * wrongCount 与 correctStreak 允许为 null（历史数据可能没写），直接拆箱做加法会在
     * 「答错一次」这条最常见的路径上抛 NPE。所有参与算术运算的计数都要经过这里。
     *
     * @param value 数据库里读出的计数值，可为 null
     * @return 计数值；入参为 null 时返回 0
     */
    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 「清空消解信息」的小工具
     *
     * updateById 会跳过 null 字段，因此「把 resolvedAt 改成 null」只能用 UpdateWrapper 显式 set。
     * 这段构造代码与状态迁移无关，抽出来免得主流程变长。
     *
     * 坑：wrapper 里没有 status 条件，这条 UPDATE 会无条件清空消解信息，
     * 调用方必须先确认原来确实是已消解状态。
     */
    private static final class LambdaUpdateWrapperHolder {

        /**
         * 清空指定错题条目的消解时间与消解方式
         *
         * 只清这两列、不动其它字段，一条 UPDATE 即可；「读出来、置空、updateById」既多一次查询，
         * 又因为 updateById 跳过 null 而根本达不到目的。
         *
         * 坑：本方法没有事务注解，依赖调用方（handleWrong）已经处在事务里，
         * 从非事务方法里调用它会让这条 UPDATE 单独提交，与前面的更新失去原子性。
         *
         * @param service 错题服务实例，用来拿 baseMapper（父类字段）
         * @param id 错题条目 ID，不存在时 UPDATE 影响 0 行，不报错
         */
        private static void clearResolved(QuizWrongServiceImpl service, Long id) {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StudioQuizWrong> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            // ① 按主键定位，调用方已持有条目实体，不需要再按「用户 + 题目」查
            wrapper.eq(StudioQuizWrong::getId, id)
                    // ② 两列同时清空，避免「有类型无时间」的矛盾状态
                    .set(StudioQuizWrong::getResolvedAt, null)
                    .set(StudioQuizWrong::getResolveType, null);
            // ③ 传 null 实体：只执行 wrapper 里的 set，不按实体字段更新
            service.baseMapper.update(null, wrapper);
        }

        private LambdaUpdateWrapperHolder() {
        }
    }
}

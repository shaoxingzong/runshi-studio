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
 * 本类的核心是 applyOutcome，它是错题本状态机的唯一写入口。
 * 所有「什么时候入本、什么时候消解、消解后再错怎么办」的规则都在那一个方法里，
 * 别处不允许直接改 status / correct_streak——
 * 规则一旦分散，就会出现「答题页说已消解、错题本页还显示待复习」的不一致，
 * 而这种不一致不报错，只能靠用户投诉发现。
 *
 * 为什么过滤停用题用子查询而不是清理条目：
 * 停用是可逆的（管理员随时可能重新启用），如果停用时把条目删掉，
 * 重新启用后用户的错题记录就永久消失了。用 inSql 在查询侧过滤，
 * 重新启用后条目自动回来。代价是每条查询多一个子查询——但它走
 * studio_quiz_question 的主键索引，代价可以忽略。
 *
 * 列表与计数必须用同一个过滤条件，否则会出现
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
     * 停用（ status=1）与删除（ deleted_at≠0）的题都不出现在错题本里。
     * 抽成常量是为了让列表与计数用同一个字符串——
     * 两处各写一份，改一处漏一处就会出现「计数 9、列表 7」。
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
     * <p>为什么这样设计：错题本是一个状态机——答错加入待复习、在错题本里答对累加连对、
     * 连对达到阈值才消解、已消解的题再答错回到待复习。这些迁移必须只有一个写入口，
     * 否则「提交作答」与「手动标记已掌握」两条入口各写一套规则，
     * 迟早出现「答题页说已消解、错题本页还显示待复习」的不一致，而这种不一致不报错，
     * 只能靠用户投诉发现。因此本方法被设计成「进来一次，出结果一次」：
     * 先查一次当前条目，再按对错分成两条支路，两支都只改这一条记录。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先按「用户 + 题目」查已有条目：这个查询必须发生在任何写操作之前，
     *     因为「首次入本」与「再错一次」的落库方式不同（insert 还是 update）；</li>
     *     <li>② 答错走 handleWrong：它内部再区分「条目不存在」与「条目已存在」，
     *     两者最终的落库规则一致，只有返回给前端的动作类型不同；</li>
     *     <li>③ 答对走 handleCorrect：它只推进连对进度，绝不为「从没做错过的题」
     *     建条目——那会把「已消解」的含义变成「做对过的题数」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法带事务，但它做的事是「读一次 + 写一次」，并没有行锁保护。
     * 同一用户对同一题并发提交两次时，两个线程可能都查到「条目不存在」然后都去 insert，
     * 撞唯一键的那一个会抛异常回滚。真正拦住重复提交的是作答表上的唯一键，
     * 这里不重复做那层防护——错题本的写入总是跟在「作答记录插入成功」之后。
     *
     * @param userId 用户 ID，必须是当前登录且在队的成员（准入由调用方校验，本方法不再查成员表）
     * @param questionId 题目 ID，必须是调用方刚刚判过分的题；传错只会写脏一条错题记录
     * @param correct 本次是否答对；答错会累加错误次数并把连对清零
     * @param resolveStreak 消解阈值（连续答对几次算掌握），小于 1 时按 1 处理
     * @return 本次对错题本的影响，取值 added / reopened / progress / resolved / none；
     *         永不为 null，调用方不必判空
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
     * <p>为什么这样设计：「首次入本」与「再错一次」的落库规则被刻意写成一致的——
     * 都是「回到待复习、错误次数 +1、连对归零」。这样「已消解 → 待复习」这个迁移
     * 不需要任何额外代码：只要每次答错都无条件地把状态写回待复习并清掉连对，
     * 已消解的条目自然就退回去了。区别只体现在返回给前端的动作类型
     * （added 还是 reopened），用来决定提示文案，不影响数据。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 条目不存在时新建：这是「这道题第一次做错」，错误次数从 1 开始，
     *     连对从 0 开始，lastWrongAt 记当前时间（错题本按它排序，最久没碰的优先）；</li>
     *     <li>② 条目已存在时先记下它原来是否已消解：这个判断必须在更新之前做，
     *     因为更新之后 status 就被改成待复习了，再也看不出原来是什么状态；</li>
     *     <li>③ 用「新建实体 + setId」的方式做部分更新：只 set 需要改的字段，
     *     updateById 会跳过 null 字段，因此不会误改其它列；</li>
     *     <li>④ 已消解过的条目要额外清空 resolvedAt / resolveType：这两列不能用实体更新
     *     清空（null 会被跳过），必须走 wrapper 显式 set。</li>
     * </ol>
     *
     * <p>容易踩的坑：错误次数的累加用的是「读出来 +1 再写回」，而不是 SQL 里的
     * `wrong_count = wrong_count + 1`。在并发提交同一题时这会丢一次计数——
     * 但作答表上的唯一键已经把「同一批同一题只能提交一次」拦住了，
     * 因此本方法实际不会并发执行。若将来允许多批次重复作答，这里要改成自增表达式。
     *
     * @param userId 用户 ID，仅在新建条目时使用
     * @param questionId 题目 ID，仅在新建条目时使用
     * @param existing 该用户对该题已有的错题条目；为 null 表示首次做错
     * @return 本次影响：首次入本返回 added，已存在返回 added（未消解）或 reopened（已消解过）
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
     * <p>为什么这样设计：答对分三种情况，规则完全不同，必须分清楚。
     * 一是不在错题本里——什么都不做。一道从来没做错过的题答对了，没有任何状态需要更新；
     * 如果给它建一条「已消解」的条目，那「已消解」的含义就变成了「做对过的题数」，
     * 与它真正的语义（错过、之后又掌握了）不符，学习概览里的数字也会失去意义。
     * 二是条目已消解——同样什么都不做，既不推进连对也不刷新 resolvedAt，
     * 否则「什么时候掌握的」这个时间点会随着每次答对一直往后漂。
     * 三是在待复习——推进连对，达到阈值就消解，否则返回还差几次。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 不在错题本里直接返回 none：这是「做对的题不进错题本」这条规则的落点；</li>
     *     <li>② 已消解直接返回 none：不推进连对、不刷新消解时间，保持时间点的稳定；</li>
     *     <li>③ 阈值先夹到至少 1：配置成 0 或负数时，下面「newStreak >= threshold」会
     *     让第一次答对就消解，虽然不报错但语义明显不对，这里兜住它；</li>
     *     <li>④ 连对次数 +1 后再与阈值比较：必须先加再比，否则「差一次」的那次答对
     *     不会触发消解，用户会觉得明明答对了却没被认定；</li>
     *     <li>⑤ 达阈值时同时写 status、resolvedAt、resolveType：三个字段必须在同一次
     *     update 里写入，否则中途失败会出现「已消解但没有消解时间」的脏状态；</li>
     *     <li>⑥ 未达阈值只更新连对，返回 remainingStreak 供前端提示「再答对 N 次掌握」。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的 remainingStreak 在 resolved 时给的是 0 而不是 null，
     * 而 progress 时给的是正数。前端若统一按「null 表示无需提示」来处理，
     * 就会在刚消解时显示「再答对 0 次」——两种情况的判断要看 action 而不是看数字。
     *
     * @param existing 该用户对该题已有的错题条目；为 null 表示这道题没做错过
     * @param resolveStreak 消解阈值（连续答对几次算掌握），小于 1 时按 1 处理
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
     * <p>为什么这样设计：列表与计数必须用同一套过滤条件，否则会出现
     * 「概览显示 9 条待复习、列表只有 7 条」这种自相矛盾的界面，而它不会报错，
     * 只会让用户觉得数据不可信。因此「可见题目」这个条件被抽成常量
     * VISIBLE_QUESTION_SQL，列表、计数、错题重刷候选池三处共用同一个字符串。
     * 另一个取舍是按教材/章节筛选时「先查题目 ID 再用 IN 过滤」而不是 JOIN：
     * 本项目零手写 SQL，Wrapper 表达不了 JOIN，而错题量在几十到几百的量级，
     * 两次查询完全够用，换来的是所有查询条件都能走各自表的索引。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时兜一个空对象：调用方可能不传任何筛选条件，
     *     兜住之后下面就不必到处判空；</li>
     *     <li>② 拼基础条件：用户、状态、题目可见性、排序。
     *     排序按 lastWrongAt 升序（最久没复习的先来），因为错题本的价值在于
     *     「把忘掉的捡回来」，而不是「反复刷最近刚错的那道」；</li>
     *     <li>③ 范围筛选：先查范围内题目 ID，为空则直接返回空页——
     *     这一步不能省，否则会带着一个空 IN 条件去查库（有些数据库会直接报错）；</li>
     *     <li>④ 分页查询实体：分页参数走兜底纠正，不因页码非法而报错；</li>
     *     <li>⑤ 实体转 VO：题目信息一次批量查完，避免逐行查题目的 N+1；</li>
     *     <li>⑥ 组装 VO 分页对象：必须把 current / size / total 一并带过去，
     *     否则前端拿到的分页控件会显示成「第 1 页、共 0 条」。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的 total 是「过滤后」的总数，与列表记录数一致；
     * 但 toItemVOs 里对「题目已被删除」的条目做了防御性跳过，
     * 因此极端情况下 records 可能少于 total。这不是 bug——
     * 正常情况下题目删除时条目已被同事务清理，跳过只是兜底。
     *
     * @param userId 用户 ID，必须是当前登录用户；本方法不校验归属，调用方负责传入自己的 ID
     * @param request 查询条件（教材名 / 章节名 / 分页参数），允许为 null
     * @return 分页结果；没有待复习条目时返回空页（records 为空列表，total 为 0），不返回 null
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
     * <p>为什么这样设计：这里是本模块少数几个「可以下发答案」的出口之一。
     * 理由是「能进错题本的前提是这道题你做错过」——已经错过一次的人看到答案
     * 是应该的，藏着反而让他无法复盘。但列表接口不带答案（见 listMyWrongByPage），
     * 因为浏览列表时并没有「我要复盘这一题」的意图，泄露答案就变成了作弊捷径。
     * 另一个细节是「不存在」与「不属于本人」返回同一个错误：如果分开报错，
     * 别人就能靠错误码枚举出「哪些 id 是真实存在的」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先校验 id 为正：负数与 0 不可能存在，早点拒绝省一次查询；</li>
     *     <li>② 查条目并校验归属：归属判断必须紧跟查询，不能等到组装 VO 之后——
     *     越早拒绝，后面的代码就越不需要考虑越权；</li>
     *     <li>③ 查题目本体：题目可能已被删除（逻辑删除后 selectById 查不到），
     *     此时给「题目已被删除」而不是「无权访问」，避免用户以为是权限问题；</li>
     *     <li>④ 先填公共字段，再补只有详情才有的答案、解析与「上次所选」：
     *     公共字段由 fillItem 负责，列表与详情共用同一份赋值逻辑；</li>
     *     <li>⑤ 「上次所选」要单独查作答表：错题条目本身只记状态，不记用户当时选了什么。</li>
     * </ol>
     *
     * <p>容易踩的坑：正确答案取的是题目表里的当前值，不是作答记录里的快照。
     * 管理员改过答案后，这里显示的是新答案，而作答记录里存的是提交时的旧答案——
     * 复盘时两者可能对不上。这是刻意的取舍：详情页要回答「正确答案是什么」，
     * 而不是「当时系统认为的正确答案是什么」。
     *
     * @param userId 当前登录用户 ID，用于归属校验；不是本人的条目一律按「不存在」处理
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛参数错误
     * @return 错题详情，含正确答案与解析
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法时抛 A0401；
     *         条目不存在或不属于当前用户时抛 A0301；题目已被删除时抛 A0402
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
     * <p>为什么这样设计：自动消解要求连续答对若干次，但用户可能已经通过别的材料
     * 掌握了这道题，不想再刷几遍才能让它从错题本里消失。手动入口就是给这种情况留的，
     * 它跳过连对计数，直接把状态置为已消解。关键在于它是幂等的（重复调用结果一致）：
     * 已经消解时直接返回成功，且不刷新 resolvedAt——
     * 重复点击不该把「什么时候掌握的」这个时间点改掉，否则用户每点一次
     * 这个时间就往后跳一次，错题本的时间线就失去了意义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正：与详情接口保持同一套入参校验；</li>
     *     <li>② 查条目并校验归属：不查归属就能改别人的错题本，
     *     这是越权，必须在写操作之前拦住；</li>
     *     <li>③ 已消解直接返回 true：幂等的落点，不写库、不改时间；</li>
     *     <li>④ 未消解才更新：同时写 status、resolvedAt、resolveType，
     *     resolveType 记为「手动」，与自动消解区分开，便于事后统计。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有事务注解。它只写一行、且没有任何跨表联动，
     * 加事务只会多一层代理开销；但如果将来要在标记掌握时顺带清理别的东西，
     * 就必须补上事务，否则会出现「错题本消解了、关联数据没清」的半成品状态。
     * 另外返回值直接透传 updateById 的结果，值为 false 时说明该行已不存在——
     * 但存在性在②已经校验过，因此这只可能是并发删除，调用方按失败处理即可。
     *
     * @param userId 当前登录用户 ID，用于归属校验
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛参数错误
     * @return true 表示成功，包含「本来就已经消解」的情况；false 表示更新未生效
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法时抛 A0401；
     *         条目不存在或不属于当前用户时抛 A0301
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
     * <p>为什么这样设计：这个数字必须和错题本列表的条数完全一致，否则用户会看到
     * 「概览说 9 条待复习、点进去只有 7 条」这种自相矛盾的界面。因此这里用的过滤条件
     * 与 listMyWrongByPage 完全相同，连「可见题目」的子查询都是同一个常量字符串——
     * 两处各写一份，改一处漏一处就会立刻出现上面那种不一致，而且它不会报错。
     * 另一个细节是返回值用 Math.toIntExact 而不是强转：条数理论上不会溢出 int，
     * 但万一真溢出，抛异常比悄悄截断成一个负数更容易发现。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按用户 + 待复习状态计数，再叠加题目可见性过滤：
     *     三个条件缺一不可，少用户条件会统计到别人的错题，少状态条件会把已消解的算进来；</li>
     *     <li>② 转成 int 返回：概览接口的字段是 int，转换失败会抛异常而不是静默截断。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里统计的是「条目数」而不是「题目数」。同一个用户在错题本里
     * 对一道题只会有一条记录（由用户 + 题目的唯一键保证），所以两者恰好相等；
     * 若将来允许多条，这个数字的含义就要重新定义。
     *
     * @param userId 用户 ID；传错会统计到别人的数据，调用方必须传当前登录用户
     * @return 待复习条目数；没有错题时返回 0（不是 null）
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
     * <p>为什么这样设计：这个数字的口径是「当前状态」，不是「历史累计」。
     * 一道已消解的题如果又被答错，它会回到待复习，这个数字就应该减 1。
     * 如果按历史累计来算，它会只增不减，对用户没有任何行动指引——
     * 「你已经掌握了 37 题」听起来不错，但其中可能有 10 题又忘光了。
     * 同样地，题目被停用或删除时这里也不计入，与待复习数保持同一套可见性口径。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按用户 + 已消解状态计数，并叠加题目可见性过滤：停用的题不显示，
     *     但条目本身保留，重新启用后会自动回到这个数字里；</li>
     *     <li>② 转成 int 返回，溢出时抛异常而不是静默截断。</li>
     * </ol>
     *
     * <p>容易踩的坑：待复习数与已消解数相加，不一定等于「错题本总条目数」——
     * 因为停用题目的条目两边都不计。这是期望行为：概览要回答「现在还能复习什么」，
     * 而不是「历史上错过多少」。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 已消解条目数（按当前状态统计）；没有时返回 0（不是 null）
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
     * <p>为什么这样设计：错题重刷模式的候选池必须与顺序模式严格区分开——
     * 顺序模式排除「已作答」的题，错题模式只出「待复习」的题，两个集合不重叠，
     * 用户才不会在两个入口看到同一批题。因此本方法只认 status=待复习，
     * 并且同样过滤掉停用与删除的题目（否则会出一批点进去没有内容的题）。
     * 排序按 lastWrongAt 升序：错题本的价值在于「隔一段时间再考一次」，
     * 总是先出最近刚错的那几道等于没复习。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 只查题目 ID 列并排序：候选池只用来「挑题」，不需要题目内容，
     *     少查列能减少回表，也让排序更轻；</li>
     *     <li>② 空结果直接返回空列表：调用方会用 isEmpty 判断「错题本已清空」，
     *     不需要为 null 再写一个分支；</li>
     *     <li>③ 没有范围条件时直接返回：省掉一次「查范围内题目」的查询，
     *     这是「不限教材章节」的常见路径；</li>
     *     <li>④ 有范围条件时先查范围内题目 ID，再用 filter 保留原顺序：
     *     不能反过来「先按范围查题目、再与错题取交集」，那会丢掉 lastWrongAt 的排序。</li>
     * </ol>
     *
     * <p>容易踩的坑：filter 用的是 List.contains，范围题量大时是 O(n²)。
     * 这里没有改成 HashSet，是因为一次错题重刷的候选量由题量配置封顶（几十条），
     * 而「保留原顺序」比这点开销重要得多。若将来候选池扩到上千条，应改成
     * 先建 HashSet 再 filter。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @param textbookName 教材名称，为 null 表示不限；空串应按 null 处理，由调用方归一
     * @param chapterName 章节名称，为 null 表示不限；只传章节不传教材在语义上无法定位，调用方需先校验
     * @return 题目 ID 列表，按「最久没复习」升序；没有待复习题时返回空列表（不是 null）
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
     * <p>为什么这样设计：状态机每次都要先知道「这条记录在不在」，而唯一能定位它的
     * 就是用户与题目这两个字段（表上有对应的唯一键）。用 getOne 而不是先 list 再取第一个：
     * 唯一键保证最多一条，getOne 若查出多条会直接抛异常，等于把「唯一键被破坏」
     * 这件事暴露出来，而不是悄悄取第一条掩盖问题。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 两个等值条件一起传进 wrapper：少一个都会查出别人的记录或别的题的记录，
     *     而错题本状态机一旦读到错误的条目，后续的累加与消解就全部作用在错的题上。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回 null 表示「这道题没进过错题本」，这是正常状态而不是错误，
     * 调用方必须判空。若把「查不到」当成异常抛出，第一次做错题时就会失败。
     *
     * @param userId 用户 ID
     * @param questionId 题目 ID
     * @return 错题条目；不存在时返回 null，调用方必须判空
     */
    private StudioQuizWrong findByUserAndQuestion(long userId, long questionId) {
        return this.getOne(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getQuestionId, questionId));
    }

    /**
     * 取范围内（教材 / 章节，均可空）的题目 ID
     *
     * <p>为什么这样设计：错题表里只有 question_id，没有教材与章节——
     * 教材章节是题目表上的名字列，不是独立实体。因此「按章节筛错题」只能先在题目表里
     * 找出该范围的题目 ID，再拿这批 ID 去过滤错题。这个顺序不能反过来：
     * 先筛错题再判断题目属于哪一章，就得逐条查题目（N+1），而这里只要一次查询。
     * 条件用「非 null 才拼接」而不是「null 就匹配全部」：后者在 SQL 里要写
     * `column = ? OR ? IS NULL`，索引会失效。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 只查 id 列：调用方只拿它做 IN 过滤，不需要题目内容；</li>
     *     <li>② 教材名与章节名分别按「非 null 才加条件」拼接，两个都不传时
     *     等于查全表——调用方必须先用「是否真的需要过滤」判断，不要无条件调用。</li>
     * </ol>
     *
     * <p>容易踩的坑：两个参数都为 null 时本方法会返回全部题目 ID，
     * 看起来像「不限范围」的正确答案，实际上是一次全表扫描。因此调用方一定要像
     * listMyWrongByPage 那样先判断「有没有筛选条件」，没有就根本不调这个方法。
     *
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限
     * @return 题目 ID 列表；范围内没有题时返回空列表（不是 null）
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
     * <p>为什么这样设计：错题复盘时只显示正确答案是不够的——用户看到「正确答案 ABD」，
     * 却不知道自己当时选了什么，就无法定位错在哪一步。因此详情页要把「上次所选」
     * 一起带出来。只取最近一次答错（按记录 ID 倒序取第一条），而不是取最近一次作答：
     * 答对的那次不构成「错题复盘」的素材，而且答对时条目可能已经消解了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 三个条件：用户、题目、结果为「错误」。少「结果」条件会把答对的那次
     *     也取出来，复盘时就变成了「你上次选对了」，与错题本的用途相悖；</li>
     *     <li>② 按 id 倒序并 LIMIT 1：id 自增，倒序即「最近一次」；
     *     用 last 直接拼 LIMIT 而不是查全部再取第一条，避免把整道题的历史作答都拉回来；</li>
     *     <li>③ 空结果返回 null：调用方用它判断「有没有可展示的上次所选」，
     *     前端据此决定是否渲染那一行。</li>
     * </ol>
     *
     * <p>容易踩的坑：last("LIMIT 1") 是字符串拼接，只允许拼常量。若将来要把用户输入
     * 拼进来，必须改成参数绑定，否则就是 SQL 注入。
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
     * <p>为什么这样设计：分页查出 20 条错题条目后，如果逐条去查题目，
     * 就是 1 次分页查询 + 20 次题目查询，即典型的 N+1（一次主查询引发 N 次子查询）。
     * 这里改成先收集全部 questionId，一次 selectByIds 把题目捞回来放进 Map，
     * 再在内存里逐条取——查询次数恒为 2，与页大小无关。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 空入参直接给空列表：调用方可能传空页，不必为此走一遍查询；</li>
     *     <li>② 收集题目 ID 并一次查完，用 toMap 转成「id → 题目」的映射：
     *     合并函数 (a, b) -> a 用来兜住重复 id（正常不会出现，但 Map 不允许重复键，
     *     没有它就会抛 IllegalStateException）；</li>
     *     <li>③ 逐条组装，题目缺失时跳过：题目删除时条目本该被同事务清理，
     *     这里跳过是防御性写法，避免一条脏数据让整页 500；</li>
     *     <li>④ 公共字段交给 fillItem：列表与详情必须显示同一套字段，
     *     各写一份赋值迟早会出现「列表有、详情没有」。</li>
     * </ol>
     *
     * <p>容易踩的坑：因为③会跳过缺失的条目，返回的列表长度可能小于入参长度。
     * 因此不能假设「第 i 条 VO 对应第 i 条实体」，需要对应关系时只能靠 id 回查。
     *
     * @param entries 错题条目列表，可为 null
     * @return 错题条目 VO 列表；入参为空时返回空列表（不是 null）
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
     * <p>为什么这样设计：列表与详情要展示的字段有一大半是重合的（题干、题型、
     * 教材章节、错误次数、连对进度、消解时间）。如果各写一份赋值，改字段时漏掉一处，
     * 就会出现「列表里有教材名、详情里没有」这种只有用户能发现的差异。
     * 抽成一个方法之后，公共字段只有一个定义，详情只需在此基础上追加答案与解析。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先填条目自身的字段（id、状态、次数、时间）：它们来自错题表，
     *     即使题目被停用也仍然有效；</li>
     *     <li>② 再填题目上的字段（题型、题干、教材章节）：这些是「题目的属性」，
     *     管理员改题后这里显示的是最新值，不是用户答题时的快照。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法入参类型是 QuizWrongItemVO，而 QuizWrongDetailVO 是它的子类，
     * 因此同一个方法既能填列表也能填详情。若将来两个 VO 的字段出现分叉，
     * 要先把分叉的字段挪出去，而不是在这里加 if 判断类型。
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
     * <p>为什么这样设计：页码与页大小来自前端，传错（null、0、负数、过大）是常态，
     * 没必要让整个查询失败——这是项目里所有分页接口的统一口径。
     * 同时必须设上限：不加限制的话，前端传 pageSize=100000 就成了一次全表拉取，
     * 既慢又可能把服务打挂。因此这里是「纠正」而不是「校验」：
     * 非法值一律换成默认值，超限值一律压到上限。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 页码小于 1 一律当第 1 页：负数页码在 SQL 里会算出负偏移量，直接报错；</li>
     *     <li>② 页大小非法时用默认值，合法时压到上限：Math.min 保证结果不超过上限。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只处理「参数本身是否合理」，不处理「页码超出总页数」——
     * 那属于正常业务结果（用户可能在看最后一页之后刷新），返回空页即可，不必报错。
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
     * <p>为什么这样设计：wrongCount 与 correctStreak 在表里允许为 null（历史数据可能
     * 没有写这两列）。如果直接拆箱做加法，一个 null 就会变成 NullPointerException，
     * 而这个异常会发生在「答错一次」这条最常见的路径上，影响面比想象中大。
     * 因此所有参与算术运算的计数都要经过这里。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 三目判断后返回：null 与 0 在这里语义相同，都是「还没有计数」。</li>
     * </ol>
     *
     * <p>容易踩的坑：把 null 当成 0 意味着「分不清从未计数与计数为 0」。
     * 在本模块里两者没有区别，但如果将来要用 null 表示「不适用」，这个兜底就要重新设计。
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
     * <p>为什么这样设计：MyBatis-Plus 的 updateById 会跳过实体里的 null 字段，
     * 因此「把 resolvedAt 改成 null」这件事无法用实体更新完成——传 null 等于「不修改」。
     * 唯一可行的办法是用 UpdateWrapper 显式 set 成 null。这段 wrapper 构造代码
     * 与 handleWrong 的职责（状态迁移）无关，塞在方法体里会让主流程变长，
     * 因此抽成内部类的一个静态方法。它不做任何业务判断：传什么 id 就清哪个。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按主键定位到具体条目：这里只能用 id，不能用「用户 + 题目」，
     *     因为调用方已经拿到了条目实体；</li>
     *     <li>② 两个 set 一起写：resolvedAt 与 resolveType 必须同时清空，
     *     只清一个会留下「有消解类型却没有消解时间」的矛盾状态；</li>
     *     <li>③ 调 baseMapper.update 而不是 service.update：传 null 实体表示
     *     「不按实体更新」，只执行 wrapper 里的 set 子句。</li>
     * </ol>
     *
     * <p>容易踩的坑：wrapper 里没有加 status 条件，因此这条 UPDATE 会把
     * 该条目的消解信息无条件清空。调用方必须先确认「原来确实是已消解状态」再调，
     * 否则会把一条待复习条目的历史消解时间误删——虽然待复习条目的这两列本应为 null，
     * 但依赖这个假设不如依赖调用方的判断。
     */
    private static final class LambdaUpdateWrapperHolder {

        /**
         * 清空指定错题条目的消解时间与消解方式
         *
         * <p>为什么这样设计：只清这两列、不动其它字段，因此不需要先查再改，
         * 一条 UPDATE 就能完成。若改成「读出来、置空、updateById」，会多一次查询，
         * 而且 updateById 依然会跳过 null，根本达不到目的。
         *
         * <p>容易踩的坑：这里没有加事务注解，它依赖调用方（handleWrong）已经处在事务里。
         * 若将来从非事务方法里调用它，这条 UPDATE 会单独提交，与前面的更新失去原子性。
         *
         * @param service 错题服务实例，用来拿 baseMapper（父类字段）
         * @param id 错题条目 ID，必须存在；不存在时 UPDATE 影响 0 行，不报错
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

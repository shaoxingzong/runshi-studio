package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.config.QuizProperties;
import com.bhu.runshistudioweb.constant.QuizConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.QuizOptionCodec;
import com.bhu.runshistudioweb.manager.QuizQuestionSampler;
import com.bhu.runshistudioweb.mapper.StudioQuizAnswerMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizQuestionMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizWrongMapper;
import com.bhu.runshistudioweb.model.dto.quiz.QuizAnswerRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizBatchRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizSkipRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import com.bhu.runshistudioweb.model.enums.QuizAnswerResultEnum;
import com.bhu.runshistudioweb.model.enums.QuizModeEnum;
import com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum;
import com.bhu.runshistudioweb.model.enums.QuizWrongStatusEnum;
import com.bhu.runshistudioweb.model.vo.quiz.QuizBatchVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOptionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOverviewVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizVerdictVO;
import com.bhu.runshistudioweb.service.QuizPracticeService;
import com.bhu.runshistudioweb.service.QuizQuestionService;
import com.bhu.runshistudioweb.service.QuizWrongService;
import com.bhu.runshistudioweb.service.StudioMemberService;
import com.bhu.runshistudioweb.utils.QuizLabelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 刷题服务实现（C 端：出题、判分、跳过、概览）
 *
 * 幂等靠作答表「用户 + 批次 + 题目」唯一键：撞键时重读记录、原样返回首次判定。
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizPracticeServiceImpl extends ServiceImpl<StudioQuizAnswerMapper, StudioQuizAnswer>
        implements QuizPracticeService {

    /** 顺序模式：排除已作答（正确或错误）的题 */
    private static final Set<Integer> ANSWERED_RESULTS = Set.of(
            QuizAnswerResultEnum.CORRECT.getValue(), QuizAnswerResultEnum.WRONG.getValue());

    private final StudioMemberService studioMemberService;

    private final QuizQuestionService quizQuestionService;

    private final QuizWrongService quizWrongService;

    private final StudioQuizQuestionMapper studioQuizQuestionMapper;

    private final StudioQuizWrongMapper studioQuizWrongMapper;

    private final QuizOptionCodec quizOptionCodec;

    private final QuizProperties quizProperties;

    // 开批

    /** 开一批题（三种模式：顺序 / 错题重刷 / 随机） */
    @Override
    public QuizBatchVO startBatch(QuizBatchRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // 准入必须在所有查询之前：越权用户不该消耗任何一次数据库查询
        long userId = requireActiveMember();

        QuizModeEnum mode = QuizModeEnum.of(request.getMode());
        // 模式是闭集：非法值立刻报错，不默认成某个模式（否则用户以为在刷错题、实际在刷顺序）
        ThrowUtils.throwIf(mode == null, ErrorCode.PARAMS_ERROR,
                "刷题模式取值非法，合法值为 " + QuizModeEnum.valuesText());

        String textbookName = StrUtil.trimToNull(request.getTextbookName());
        String chapterName = StrUtil.trimToNull(request.getChapterName());
        // 章节必须依附于教材：只传章节不传教材时，无法确定是哪本书的哪一章
        ThrowUtils.throwIf(chapterName != null && textbookName == null,
                ErrorCode.PARAMS_ERROR, "选择章节时必须同时指定教材");

        // 题量收敛：null / 0 / 超大值都纠正到 [1, 上限]，不因此报错
        int size = QuizQuestionSampler.resolveSize(request.getSize(),
                quizProperties.getDefaultSize(), quizProperties.getMaxSize());

        // 三种模式唯一分岔的地方：之后全部合流
        List<Long> questionIds = switch (mode) {
            case SEQUENTIAL -> sampleSequential(userId, textbookName, chapterName, size,
                    // restart 用 Boolean.TRUE.equals 判真：getRestart() 为 null 时按 false 处理
                    Boolean.TRUE.equals(request.getRestart()));
            case WRONG -> sampleWrong(userId, textbookName, chapterName, size);
            case RANDOM -> sampleRandom(textbookName, chapterName, size);
        };

        // 按模式给不同提示：用户需要知道「下一步该做什么」
        if (questionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, emptyMessage(mode, textbookName, chapterName));
        }

        Map<Long, StudioQuizQuestion> questionMap = studioQuizQuestionMapper.selectByIds(questionIds).stream()
                // 合并函数 (a, b) -> a 兜住重复 id：Map 不允许重复键，没有它会抛异常
                .collect(Collectors.toMap(StudioQuizQuestion::getId, q -> q, (a, b) -> a));
        List<StudioQuizQuestion> ordered = new ArrayList<>(questionIds.size());
        for (Long id : questionIds) {
            StudioQuizQuestion question = questionMap.get(id);
            // 查不到说明这条题刚被逻辑删除，跳过即可，不必让整批失败
            if (question != null) {
                ordered.add(question);
            }
        }
        // 题目可能在这几毫秒里被停用/删除：全部失效时按「无题」处理
        ThrowUtils.throwIf(ordered.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "题目已被下线，请重新开始");

        // 标记哪些题在错题本里（只给标记，不泄露答案）
        Set<Long> wrongBookIds = pendingWrongQuestionIds(userId);

        List<QuizQuestionVO> questions = ordered.stream()
                .map(question -> {
                    QuizQuestionVO vo = toQuestionVO(question);
                    // 标记用于前端提示「这题你错过」，不含任何答案信息
                    vo.setInWrongBook(wrongBookIds.contains(question.getId()));
                    return vo;
                })
                .collect(Collectors.toList());

        QuizBatchVO batch = new QuizBatchVO();
        // 批次 ID 由服务端生成：让前端传等于把「本轮是哪几题」的解释权交出去
        batch.setBatchId(IdWorker.getId());
        batch.setMode(mode.getValue());
        // total 用组装后的真实条数，而不是请求的 size：题量不足时两者不同
        batch.setTotal(questions.size());
        batch.setQuestions(questions);
        return batch;
    }

    /** 顺序模式：本章启用题里排除「已作答」的，按题序取前 size 个 */
    private List<Long> sampleSequential(long userId, String textbookName, String chapterName,
                                        int size, boolean restart) {
        // 顺序模式必须明确刷哪一章：不指定的话「从哪开始」没有答案
        ThrowUtils.throwIf(textbookName == null || chapterName == null,
                ErrorCode.PARAMS_ERROR, "顺序刷题必须选择教材与章节");

        // 取本章全部启用题，按题序排好（过滤留给下一步）
        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        // 本章一道题都没有：这是「还没录题」，与「刷完了」是两件事
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "本章还没有题目");
        }
        if (!restart) {
            // 一次查出全部已作答的题再一次剔除，避免逐题查询的 N+1
            Set<Long> answered = answeredQuestionIds(userId);
            candidates = QuizQuestionSampler.excludeAnswered(candidates, answered);
            // 剔完为空 = 本章刷完：给出可操作的下一步，而不是只说「没有题目」
            if (candidates.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "本章已全部刷完，可勾选「重新刷本章」再刷一遍");
            }
        }
        // 只截断不重排：题序由查询保证，重排会破坏「从第一章往后走」的语义
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    /** 错题模式：待复习的错题，最久没复习的先来 */
    private List<Long> sampleWrong(long userId, String textbookName, String chapterName, int size) {
        // 过滤与排序都在错题本服务里完成，这里不重复实现
        List<Long> candidates = quizWrongService.listPendingQuestionIds(userId, textbookName, chapterName);
        // 空 = 错题本已清空，这是好消息，提示要写清楚
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "错题本已清空，暂时没有要复习的题");
        }
        // 排序已保证，只截断
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    /** 随机模式：范围内全部启用题 → 服务端洗牌 → 取前 size 个 */
    private List<Long> sampleRandom(String textbookName, String chapterName, int size) {
        // 不排除已作答、不看错题本：抽查语义，三者职责因此不重叠
        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        // 区分「题库为空」与「该范围为空」：前者要去录题，后者要改筛选条件
        ThrowUtils.throwIf(candidates.isEmpty(), ErrorCode.NOT_FOUND_ERROR,
                textbookName == null ? "题库还没有题目" : "该范围内暂无题目");
        // 内存洗牌后截断；生产路径不需要可复现，random 传 null
        return QuizQuestionSampler.takeRandom(candidates, size, null);
    }

    // 提交作答

    /** 提交作答并由服务端判分（幂等） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizVerdictVO submitAnswer(QuizAnswerRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        // 题目不存在（含已逻辑删除）：后续所有判断都依赖这条记录，必须先拦
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // 先查非法字符与重复，再归一化：顺序反了会让非法字符被静默丢弃
        Character invalid = QuizLabelUtils.findInvalidLabel(request.getChosenLabels());
        ThrowUtils.throwIf(invalid != null, ErrorCode.PARAMS_ERROR,
                "答案包含非法字符或重复标签：" + invalid);
        String chosen = QuizLabelUtils.normalize(request.getChosenLabels());
        // 归一后为空说明用户什么都没选：这是「提交」接口，空提交没有意义
        ThrowUtils.throwIf(chosen.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择答案");

        // 标签必须属于该题的选项（防「乱传标签刷判定」）
        List<String> optionLabels = quizOptionCodec.labelsOf(quizOptionCodec.decode(question.getOptions()));
        ThrowUtils.throwIf(!optionLabels.containsAll(QuizLabelUtils.toLabelList(chosen)),
                ErrorCode.PARAMS_ERROR, "所选选项不在本题的选项范围内");

        // 按题型校验标签数
        boolean isSingle = QuizQuestionTypeEnum.SINGLE.getValue() == question.getType();
        if (isSingle) {
            // 单选传 2 个及以上是非法请求（正常 UI 产生不了），不是判错
            ThrowUtils.throwIf(chosen.length() != 1, ErrorCode.PARAMS_ERROR, "单选题只能选择一个选项");
        }
        // 多选只选 1 个是用户的真实选择，正常判错，不拒绝

        // 判定：集合相等 == 字符串相等（两侧都已归一化为升序无分隔）
        boolean correct = chosen.equals(question.getCorrectLabels());
        int resultValue = correct
                ? QuizAnswerResultEnum.CORRECT.getValue()
                : QuizAnswerResultEnum.WRONG.getValue();

        // 幂等：先插，撞唯一键就重读并原样返回首次判定
        StudioQuizAnswer answer = new StudioQuizAnswer();
        answer.setUserId(userId);
        answer.setBatchId(request.getBatchId());
        answer.setQuestionId(question.getId());
        answer.setChosenLabels(chosen);
        // 快照：管理员事后改题不影响这条记录的可解释性
        answer.setCorrectLabels(question.getCorrectLabels());
        answer.setResult(resultValue);

        StudioQuizAnswer existing = null;
        try {
            boolean saved = this.save(answer);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录作答失败");
        } catch (DuplicateKeyException e) {
            existing = findAnswer(userId, request.getBatchId(), question.getId());
            log.info("作答撞唯一键（重复提交），按幂等返回 | userId={} | questionId={}",
                    userId, question.getId());
        }

        // 重复提交：返回首次判定，action 固定 none——错题本不能再被推进一次
        if (existing != null) {
            return buildVerdict(userId, question, existing, "none", null);
        }

        // 首次提交才更新错题本状态机（跳过不走这里）
        QuizWrongService.Outcome outcome = quizWrongService.applyOutcome(
                userId, question.getId(), correct, quizProperties.getResolveStreak());

        return buildVerdict(userId, question, answer, outcome.action(), outcome.remainingStreak());
    }

    // 跳过

    /** 跳过当前题（不判分、不碰错题本） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean skipQuestion(QuizSkipRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        // 题目不存在：连记录都不写，否则会留下指向不存在题目的作答记录
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // 组装跳过记录：result=跳过，chosen 为空
        StudioQuizAnswer answer = new StudioQuizAnswer();
        answer.setUserId(userId);
        answer.setBatchId(request.getBatchId());
        answer.setQuestionId(question.getId());
        // 跳过不选任何东西：存 NULL，绝不存空串（全局约定「空值存 NULL」）
        answer.setChosenLabels(null);
        // 仍存正确答案快照：便于事后知道当时这道题的答案是什么
        answer.setCorrectLabels(question.getCorrectLabels());
        answer.setResult(QuizAnswerResultEnum.SKIPPED.getValue());

        try {
            boolean saved = this.save(answer);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录跳过失败");
        } catch (DuplicateKeyException e) {
            log.info("跳过撞唯一键（重复提交），按幂等返回 | userId={} | questionId={}",
                    userId, question.getId());
        }
        return true;
    }

    // 概览

    /** 学习概览（作答数、正确率、错题本待复习 / 已消解） */
    @Override
    public QuizOverviewVO getOverview() {
        // 非成员不该看到任何统计数据
        long userId = requireActiveMember();
        // 一次聚合同时拿两个数，避免两次查询之间数据变化导致自相矛盾
        int[] counts = countAnsweredAndCorrect(userId);

        QuizOverviewVO vo = new QuizOverviewVO();
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        // 分母为 0 时返回 null：0% 读起来像「全错」，那是错误的信息
        vo.setAccuracy(counts[0] == 0 ? null : (double) counts[1] / counts[0]);
        // 与错题本列表同一套过滤条件，保证概览数字与列表条数一致
        vo.setWrongPending(quizWrongService.countPending(userId));
        vo.setWrongResolved(quizWrongService.countResolved(userId));
        return vo;
    }

    /** 一次查询同时取「累计作答数」与「累计答对数」 */
    @Override
    public int[] countAnsweredAndCorrect(long userId) {
        // 一条查询同时拿总数与正确数：两次 COUNT 是不必要的
        QueryWrapper<StudioQuizAnswer> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(*) AS total",
                        "SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) AS correct_cnt")
                .eq("user_id", userId)
                // 跳过不算作答：把跳过算进分母会让正确率虚低
                .in("result", ANSWERED_RESULTS);
        List<Map<String, Object>> rows = this.listMaps(wrapper);
        // 理论上聚合一定有一行，兜住方言差异下的空结果
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        Map<String, Object> row = rows.get(0);
        // 走 asInt 的 Number 分支：COUNT 在部分驱动下返回 BigInteger，不能强转
        return new int[]{asInt(row.get("total")), asInt(row.get("correct_cnt"))};
    }

    @Override
    public Page<StudioQuizAnswer> listByBatch(long userId, long batchId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        // 两个条件缺一不可：少用户条件会越权，少批次条件会混入其它轮次
        wrapper.eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                // ID 升序即操作顺序，前端据此回放进度
                .orderByAsc(StudioQuizAnswer::getId);
        // 批次题量受配置封顶，一次取完即可
        return this.page(new Page<>(1, QuizConstant.MAX_PAGE_SIZE), wrapper);
    }

    // 私有：组装

    /** 组装判定结果（含答案与解析——本模块唯一的下发出口） */
    private QuizVerdictVO buildVerdict(long userId, StudioQuizQuestion question, StudioQuizAnswer answer,
                                       String action, Integer remainingStreak) {
        QuizVerdictVO vo = new QuizVerdictVO();
        // 题目维度：与作答记录无关，直接取当前题目
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());
        // 作答维度：全部取自记录，重复提交时才能返回首次判定的内容
        vo.setResult(answer.getResult());
        vo.setChosenLabels(answer.getChosenLabels());
        vo.setCorrectLabels(answer.getCorrectLabels());
        // 解析取当前题目的值：作答记录里没有存解析快照，这是已知取舍
        vo.setAnalysis(question.getAnalysis());
        // 错题本动作由状态机给出，本方法只搬运
        vo.setWrongBookAction(action);
        vo.setRemainingStreak(remainingStreak);

        // 顺带回填累计统计：前端不必再调一次概览接口
        int[] counts = countAnsweredAndCorrect(userId);
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        return vo;
    }

    /** 实体 → C 端题目 VO（丢弃答案与解析） */
    private QuizQuestionVO toQuestionVO(StudioQuizQuestion question) {
        QuizQuestionVO vo = new QuizQuestionVO();
        // 逐个赋值而不是 copyProperties：脱敏边界必须肉眼可见
        vo.setId(question.getId());
        vo.setType(question.getType());
        vo.setStem(question.getStem());
        // 库里是 JSON 字符串、VO 要列表，类型不一致，必须显式转换
        vo.setOptions(quizOptionCodec.decode(question.getOptions()));
        // 默认 false，调用方按需覆盖，避免漏设时给出错误提示
        vo.setInWrongBook(false);
        return vo;
    }

    /** 取该用户待复习错题的题目 ID（用于给题目打「在错题本里」的标记） */
    private Set<Long> pendingWrongQuestionIds(long userId) {
        List<StudioQuizWrong> entries = studioQuizWrongMapper.selectList(
                new LambdaQueryWrapper<StudioQuizWrong>()
                        // 只查题目 ID 一列，减少回表
                        .select(StudioQuizWrong::getQuestionId)
                        .eq(StudioQuizWrong::getUserId, userId)
                        // 只算待复习：已消解的题在用户眼里已经掌握了
                        .eq(StudioQuizWrong::getStatus, QuizWrongStatusEnum.PENDING.getValue()));
        Set<Long> ids = new HashSet<>(entries.size());
        for (StudioQuizWrong entry : entries) {
            // HashSet 让后续每题的 contains 变成 O(1)
            ids.add(entry.getQuestionId());
        }
        return ids;
    }

    /** 范围内（教材 / 章节，均可空）的启用题 ID，按题序升序 */
    private List<Long> enabledQuestionIdsOf(String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizQuestion::getId)
                // 只抽启用的题：停用是可逆下线，不该被抽到
                .eq(StudioQuizQuestion::getStatus, 0)
                // 非 null 才拼条件，避免「OR IS NULL」导致索引失效
                .eq(textbookName != null, StudioQuizQuestion::getTextbookName, textbookName)
                .eq(chapterName != null, StudioQuizQuestion::getChapterName, chapterName)
                // 与 idx_scope 同向；id 作为最终排序键保证同题序时次序稳定
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                .orderByAsc(StudioQuizQuestion::getId);
        return studioQuizQuestionMapper.selectList(wrapper).stream()
                .map(StudioQuizQuestion::getId)
                .collect(Collectors.toList());
    }

    /** 该用户「已作答（正确或错误）」的题目 ID */
    private Set<Long> answeredQuestionIds(long userId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizAnswer::getQuestionId)
                .eq(StudioQuizAnswer::getUserId, userId)
                // 只认正确与错误：跳过不是「作答过」
                .in(StudioQuizAnswer::getResult, ANSWERED_RESULTS);
        // 用本 Service 自己的 baseMapper（父类字段），不必再注入一次同一个 Mapper
        return baseMapper.selectList(wrapper).stream()
                .map(StudioQuizAnswer::getQuestionId)
                .collect(Collectors.toSet());
    }

    /** 按（用户 + 批次 + 题目）查已有作答记录 */
    private StudioQuizAnswer findAnswer(long userId, long batchId, long questionId) {
        List<StudioQuizAnswer> list = this.list(new LambdaQueryWrapper<StudioQuizAnswer>()
                .eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                .eq(StudioQuizAnswer::getQuestionId, questionId));
        // 查不到时返回 null：调用方据此决定走正常流程还是幂等返回
        return list.isEmpty() ? null : list.get(0);
    }

    /** 对外暴露的成员校验：校验当前登录用户是在队成员，并返回其用户 ID */
    @Override
    public long requireActiveMemberId() {
        return requireActiveMember();
    }

    /** 校验当前登录用户是在队成员，返回其账号 ID（内部实现） */
    private long requireActiveMember() {
        // 先判登录：避免 getLoginIdAsLong 在未登录时抛出难以识别的异常
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        // 判定权威是成员档案，不是角色标签：两者可能不同步
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以使用刷题功能");
        // 返回账号 ID，供后续按用户维度查询
        return userId;
    }

    /** 「没有题」时的提示文案 */
    private String emptyMessage(QuizModeEnum mode, String textbookName, String chapterName) {
        return switch (mode) {
            // 顺序模式：没选章节与刷完了是两种不同原因，提示必须分开
            case SEQUENTIAL -> chapterName == null
                    ? "请选择要刷的章节" : "本章已全部刷完，可勾选「重新刷本章」再刷一遍";
            // 错题模式：错题本清空是好消息，不是故障
            case WRONG -> "错题本已清空，暂时没有要复习的题";
            // 随机模式：题库整体为空要去录题，范围为空要改筛选条件
            case RANDOM -> textbookName == null ? "题库还没有题目" : "该范围内暂无题目";
        };
    }

    /** 聚合结果的数值转 int */
    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

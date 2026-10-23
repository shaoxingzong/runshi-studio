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

// 刷题服务实现（C 端：出题、判分、跳过、概览）
//
// 准入以 studio_member 表为准（与考勤模块完全同源）：
// 每个公开方法的第一步都是 assertIsActiveMember，
// 非成员抛 A0301。连教材列表都不给——他没有刷题资格。
//
// 判分只在这里发生： #submitAnswer 是唯一知道
// 「正确答案是什么」的地方，且它只在提交之后把答案回给调用方。
// 出题接口（ #startBatch）走的是 QuizQuestionVO，
// 那个类结构上就没有答案字段——这不是「记得删」，是「根本没有」。
//
// 三种模式的职责不重叠（见 QuizQuestionSampler 的类注释）：
// 顺序负责「覆盖」、错题负责「消灭」、随机负责「抽查」。
// 因此顺序模式排除的是「已作答」而不是「已掌握」——答错的题归错题重刷管，
// 在两个入口都出现会让用户困惑「怎么又是这道」。
//
// 一个刻意的取舍：不校验 questionId 属于 batchId。
// 服务端不持久化批次内容（题目不含答案，整批交给前端即可），
// 因此无法校验。最坏影响是用户给自己刷出脏的作答记录，不涉及越权或他人数据。
// 若将来要支持「历史成绩单」，才需要引入会话表。
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizPracticeServiceImpl extends ServiceImpl<StudioQuizAnswerMapper, StudioQuizAnswer>
        implements QuizPracticeService {

    // 顺序模式：排除已作答（正确或错误）的题
    private static final Set<Integer> ANSWERED_RESULTS = Set.of(
            QuizAnswerResultEnum.CORRECT.getValue(), QuizAnswerResultEnum.WRONG.getValue());

    private final StudioMemberService studioMemberService;

    private final QuizQuestionService quizQuestionService;

    private final QuizWrongService quizWrongService;

    private final StudioQuizQuestionMapper studioQuizQuestionMapper;

    private final StudioQuizWrongMapper studioQuizWrongMapper;

    private final QuizOptionCodec quizOptionCodec;

    private final QuizProperties quizProperties;

    // ==================== 开批 ====================

    @Override
    public QuizBatchVO startBatch(QuizBatchRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        QuizModeEnum mode = QuizModeEnum.of(request.getMode());
        ThrowUtils.throwIf(mode == null, ErrorCode.PARAMS_ERROR,
                "刷题模式取值非法，合法值为 " + QuizModeEnum.valuesText());

        String textbookName = StrUtil.trimToNull(request.getTextbookName());
        String chapterName = StrUtil.trimToNull(request.getChapterName());
        // 章节必须依附于教材：只传章节不传教材时，无法确定是哪本书的哪一章
        ThrowUtils.throwIf(chapterName != null && textbookName == null,
                ErrorCode.PARAMS_ERROR, "选择章节时必须同时指定教材");

        int size = QuizQuestionSampler.resolveSize(request.getSize(),
                quizProperties.getDefaultSize(), quizProperties.getMaxSize());

        List<Long> questionIds = switch (mode) {
            case SEQUENTIAL -> sampleSequential(userId, textbookName, chapterName, size,
                    Boolean.TRUE.equals(request.getRestart()));
            case WRONG -> sampleWrong(userId, textbookName, chapterName, size);
            case RANDOM -> sampleRandom(textbookName, chapterName, size);
        };

        if (questionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, emptyMessage(mode, textbookName, chapterName));
        }

        // 按 id 批量取详情（一次查询），再按 questionIds 的顺序重排：
        // selectByIds 不保证顺序，而顺序模式必须保持题序
        Map<Long, StudioQuizQuestion> questionMap = studioQuizQuestionMapper.selectByIds(questionIds).stream()
                .collect(Collectors.toMap(StudioQuizQuestion::getId, q -> q, (a, b) -> a));
        List<StudioQuizQuestion> ordered = new ArrayList<>(questionIds.size());
        for (Long id : questionIds) {
            StudioQuizQuestion question = questionMap.get(id);
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
                    vo.setInWrongBook(wrongBookIds.contains(question.getId()));
                    return vo;
                })
                .collect(Collectors.toList());

        QuizBatchVO batch = new QuizBatchVO();
        // 批次 ID 由服务端生成：让前端传等于把「本轮是哪几题」的解释权交出去
        batch.setBatchId(IdWorker.getId());
        batch.setMode(mode.getValue());
        batch.setTotal(questions.size());
        batch.setQuestions(questions);
        return batch;
    }

    // 顺序模式：本章启用题里，排除「已作答」的，取前 size 个
    //
    // @param restart true 表示「重新刷本章」，忽略排除条件
    private List<Long> sampleSequential(long userId, String textbookName, String chapterName,
                                        int size, boolean restart) {
        // 顺序模式必须明确刷哪一章：不指定的话「从哪开始」没有答案
        ThrowUtils.throwIf(textbookName == null || chapterName == null,
                ErrorCode.PARAMS_ERROR, "顺序刷题必须选择教材与章节");

        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "本章还没有题目");
        }
        if (!restart) {
            Set<Long> answered = answeredQuestionIds(userId);
            candidates = QuizQuestionSampler.excludeAnswered(candidates, answered);
            if (candidates.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "本章已全部刷完，可勾选「重新刷本章」再刷一遍");
            }
        }
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    // 错题模式：待复习的错题，最久没复习的先来
    private List<Long> sampleWrong(long userId, String textbookName, String chapterName, int size) {
        List<Long> candidates = quizWrongService.listPendingQuestionIds(userId, textbookName, chapterName);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "错题本已清空，暂时没有要复习的题");
        }
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    // 随机模式：范围内全部启用题 → 服务端洗牌 → 取前 size 个
    //
    // 不用 ORDER BY RAND()：那是对整表排序，题量上万时是明显的慢查询。
    // 只查 ID 列（索引覆盖，几千个 long 在内存里可忽略），再洗牌。
    private List<Long> sampleRandom(String textbookName, String chapterName, int size) {
        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        ThrowUtils.throwIf(candidates.isEmpty(), ErrorCode.NOT_FOUND_ERROR,
                textbookName == null ? "题库还没有题目" : "该范围内暂无题目");
        return QuizQuestionSampler.takeRandom(candidates, size, null);
    }

    // ==================== 提交作答 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizVerdictVO submitAnswer(QuizAnswerRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        // 只拒绝已删除的题（selectById 已被逻辑删除过滤）。
        // 已停用的题仍可提交：批次已经发给用户了，此时拒绝会让用户卡在一道
        // 永远答不了的题上——停用只应影响后续抽题与错题本展示

        // ① 规范化所选标签：去空白 → 大写 → 去重 → 升序
        Character invalid = QuizLabelUtils.findInvalidLabel(request.getChosenLabels());
        ThrowUtils.throwIf(invalid != null, ErrorCode.PARAMS_ERROR,
                "答案包含非法字符或重复标签：" + invalid);
        String chosen = QuizLabelUtils.normalize(request.getChosenLabels());
        ThrowUtils.throwIf(chosen.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择答案");

        // ② 标签必须属于该题的选项（防「乱传标签刷判定」）
        List<String> optionLabels = quizOptionCodec.labelsOf(quizOptionCodec.decode(question.getOptions()));
        ThrowUtils.throwIf(!optionLabels.containsAll(QuizLabelUtils.toLabelList(chosen)),
                ErrorCode.PARAMS_ERROR, "所选选项不在本题的选项范围内");

        // ③ 按题型校验标签数
        boolean isSingle = QuizQuestionTypeEnum.SINGLE.getValue() == question.getType();
        if (isSingle) {
            // 单选传 2 个及以上是非法请求（正常 UI 产生不了），不是判错
            ThrowUtils.throwIf(chosen.length() != 1, ErrorCode.PARAMS_ERROR, "单选题只能选择一个选项");
        }
        // 多选只选 1 个是用户的真实选择，正常判错，不拒绝

        // ④ 判定：集合相等 == 字符串相等（两侧都已归一化为升序无分隔）
        boolean correct = chosen.equals(question.getCorrectLabels());
        int resultValue = correct
                ? QuizAnswerResultEnum.CORRECT.getValue()
                : QuizAnswerResultEnum.WRONG.getValue();

        // ⑤ 幂等：先插，撞唯一键就重读并原样返回首次判定
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
            // 断网重试 / 双击：同一批同一题已经有记录了。
            // 语义上等同于「已经答过」，按幂等返回首次判定，而不是报错——
            // 让用户看到「本题已作答」的红条比看到判定结果更糟
            existing = findAnswer(userId, request.getBatchId(), question.getId());
            log.info("作答撞唯一键（重复提交），按幂等返回 | userId={} | questionId={}",
                    userId, question.getId());
        }

        if (existing != null) {
            return buildVerdict(userId, question, existing, "none", null);
        }

        // ⑥ 更新错题本状态机（跳过不走这里）
        QuizWrongService.Outcome outcome = quizWrongService.applyOutcome(
                userId, question.getId(), correct, quizProperties.getResolveStreak());

        return buildVerdict(userId, question, answer, outcome.action(), outcome.remainingStreak());
    }

    // ==================== 跳过 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean skipQuestion(QuizSkipRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        StudioQuizAnswer answer = new StudioQuizAnswer();
        answer.setUserId(userId);
        answer.setBatchId(request.getBatchId());
        answer.setQuestionId(question.getId());
        // 跳过不选任何东西：存 NULL，绝不存空串（全局约定「空值存 NULL」）
        answer.setChosenLabels(null);
        answer.setCorrectLabels(question.getCorrectLabels());
        answer.setResult(QuizAnswerResultEnum.SKIPPED.getValue());

        try {
            boolean saved = this.save(answer);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录跳过失败");
        } catch (DuplicateKeyException e) {
            // 同一批同一题已处理过（答过或跳过了）：幂等返回成功，不重复写。
            // 不碰错题本——跳过本来就不该有任何错题本副作用
            log.info("跳过撞唯一键（重复提交），按幂等返回 | userId={} | questionId={}",
                    userId, question.getId());
        }
        return true;
    }

    // ==================== 概览 ====================

    @Override
    public QuizOverviewVO getOverview() {
        long userId = requireActiveMember();
        int[] counts = countAnsweredAndCorrect(userId);

        QuizOverviewVO vo = new QuizOverviewVO();
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        // 分母为 0 时返回 null：0% 读起来像「全错」，那是错误的信息
        vo.setAccuracy(counts[0] == 0 ? null : (double) counts[1] / counts[0]);
        vo.setWrongPending(quizWrongService.countPending(userId));
        vo.setWrongResolved(quizWrongService.countResolved(userId));
        return vo;
    }

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
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        Map<String, Object> row = rows.get(0);
        return new int[]{asInt(row.get("total")), asInt(row.get("correct_cnt"))};
    }

    @Override
    public Page<StudioQuizAnswer> listByBatch(long userId, long batchId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                .orderByAsc(StudioQuizAnswer::getId);
        return this.page(new Page<>(1, QuizConstant.MAX_PAGE_SIZE), wrapper);
    }

    // ==================== 私有：组装 ====================

    // 组装判定结果（含答案与解析——本模块唯一的下发出口）
    private QuizVerdictVO buildVerdict(long userId, StudioQuizQuestion question, StudioQuizAnswer answer,
                                       String action, Integer remainingStreak) {
        QuizVerdictVO vo = new QuizVerdictVO();
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());
        vo.setResult(answer.getResult());
        vo.setChosenLabels(answer.getChosenLabels());
        // 用记录里的快照，而不是当前题目的答案：断网重试时两者可能已经不同
        // （管理员在这期间改了题），返回首次判定才符合「你当时看到的那道题」
        vo.setCorrectLabels(answer.getCorrectLabels());
        vo.setAnalysis(question.getAnalysis());
        vo.setWrongBookAction(action);
        vo.setRemainingStreak(remainingStreak);

        int[] counts = countAnsweredAndCorrect(userId);
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        return vo;
    }

    // 实体 → C 端题目 VO（丢弃答案与解析）
    //
    // 这是本模块的脱敏出口： QuizQuestionVO 结构上没有答案字段，
    // 因此这里「不需要记得删」——想泄露也没有地方放。
    private QuizQuestionVO toQuestionVO(StudioQuizQuestion question) {
        QuizQuestionVO vo = new QuizQuestionVO();
        vo.setId(question.getId());
        vo.setType(question.getType());
        vo.setStem(question.getStem());
        vo.setOptions(quizOptionCodec.decode(question.getOptions()));
        vo.setInWrongBook(false);
        return vo;
    }

    // 取该用户待复习错题的题目 ID（用于给题目打「在错题本里」的标记）
    private Set<Long> pendingWrongQuestionIds(long userId) {
        List<StudioQuizWrong> entries = studioQuizWrongMapper.selectList(
                new LambdaQueryWrapper<StudioQuizWrong>()
                        .select(StudioQuizWrong::getQuestionId)
                        .eq(StudioQuizWrong::getUserId, userId)
                        .eq(StudioQuizWrong::getStatus, QuizWrongStatusEnum.PENDING.getValue()));
        Set<Long> ids = new HashSet<>(entries.size());
        for (StudioQuizWrong entry : entries) {
            ids.add(entry.getQuestionId());
        }
        return ids;
    }

    // 范围内（教材/章节，均可空）的启用题 ID，按题序升序
    private List<Long> enabledQuestionIdsOf(String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizQuestion::getId)
                .eq(StudioQuizQuestion::getStatus, 0)
                .eq(textbookName != null, StudioQuizQuestion::getTextbookName, textbookName)
                .eq(chapterName != null, StudioQuizQuestion::getChapterName, chapterName)
                // 与 idx_scope 同向；id 作为最终排序键保证同题序时次序稳定
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                .orderByAsc(StudioQuizQuestion::getId);
        return studioQuizQuestionMapper.selectList(wrapper).stream()
                .map(StudioQuizQuestion::getId)
                .collect(Collectors.toList());
    }

    // 该用户「已作答（正确或错误）」的题目 ID
    //
    // 不含跳过：跳过不是「作答过」——顺序模式下跳过过的题下次还应该出现。
    private Set<Long> answeredQuestionIds(long userId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizAnswer::getQuestionId)
                .eq(StudioQuizAnswer::getUserId, userId)
                .in(StudioQuizAnswer::getResult, ANSWERED_RESULTS);
        // 用本 Service 自己的 baseMapper（父类字段），不必再注入一次同一个 Mapper
        return baseMapper.selectList(wrapper).stream()
                .map(StudioQuizAnswer::getQuestionId)
                .collect(Collectors.toSet());
    }

    // 按 (user, batch, question) 查已有作答记录
    //
    // @return 记录；不存在时返回 null
    private StudioQuizAnswer findAnswer(long userId, long batchId, long questionId) {
        List<StudioQuizAnswer> list = this.list(new LambdaQueryWrapper<StudioQuizAnswer>()
                .eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                .eq(StudioQuizAnswer::getQuestionId, questionId));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public long requireActiveMemberId() {
        return requireActiveMember();
    }

    // 校验当前登录用户是在队成员，返回其账号 ID
    //
    // 与考勤模块同一套判定：认 studio_member 表，不认 user_role。
    // 角色可能与档案不同步（有人被移出名录但角色没改），按角色放行会绕开真正的准入规则。
    //
    // @return 当前登录账号 ID
    // @throws BusinessException 未登录（A0201）或非在队成员（A0301）时抛出
    private long requireActiveMember() {
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以使用刷题功能");
        return userId;
    }

    // 「没有题」时的提示文案：按模式区分，让用户知道下一步该做什么
    private String emptyMessage(QuizModeEnum mode, String textbookName, String chapterName) {
        return switch (mode) {
            case SEQUENTIAL -> chapterName == null
                    ? "请选择要刷的章节" : "本章已全部刷完，可勾选「重新刷本章」再刷一遍";
            case WRONG -> "错题本已清空，暂时没有要复习的题";
            case RANDOM -> textbookName == null ? "题库还没有题目" : "该范围内暂无题目";
        };
    }

    // 聚合结果的数值转 int（走 Number：不同驱动下 COUNT 可能是 BigInteger）
    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

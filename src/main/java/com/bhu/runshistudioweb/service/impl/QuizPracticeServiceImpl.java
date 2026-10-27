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
 * 准入以 studio_member 表为准，非成员抛 A0301，连教材列表都不给；判分只在这里发生——
 * 出题走 QuizQuestionVO，那个类结构上就没有答案字段。
 *
 * 三种模式职责不重叠：顺序负责覆盖、错题负责消灭、随机负责抽查。
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

    /**
     * 顺序模式：排除已作答（正确或错误）的题
     *
     * 跳过不算作答——跳过只是「现在不想做」，下次还应该出现，否则用户一跳过就等于把它漏掉了。
     * 也不放「已掌握」：掌握与否由错题本判断，顺序模式只关心有没有走过一遍。
     */
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

    /**
     * 开一批题（三种模式：顺序 / 错题重刷 / 随机）
     *
     * 三种模式的选题策略必须不重叠，否则用户会在两个入口看到同一批题。本方法只按模式分派，
     * 过滤与排序留在各自的私有方法里。
     * 批次 ID 由服务端生成：让前端传等于把「本轮是哪几题」的解释权交出去，可以重放别的题的判定。
     *
     * 坑：批次本身不落库，题目不含答案，整批交给前端持有即可，服务端只需 batchId 做幂等；
     * 代价是换设备不能续答。将来要做「历史成绩单」就必须引入会话表。
     *
     * @param request 开批请求，不能为 null；顺序模式必须同时给教材与章节
     * @return 批次（含服务端生成的 batchId 与题目列表，题目结构上没有答案字段）
     * @throws BusinessException 非成员抛 A0301；模式非法、章节缺教材抛 A0401；无题可出抛 A0402
     */
    @Override
    public QuizBatchVO startBatch(QuizBatchRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ① 准入必须在所有查询之前：越权用户不该消耗任何一次数据库查询
        long userId = requireActiveMember();

        QuizModeEnum mode = QuizModeEnum.of(request.getMode());
        // ② 模式是闭集：非法值立刻报错，不默认成某个模式（否则用户以为在刷错题、实际在刷顺序）
        ThrowUtils.throwIf(mode == null, ErrorCode.PARAMS_ERROR,
                "刷题模式取值非法，合法值为 " + QuizModeEnum.valuesText());

        String textbookName = StrUtil.trimToNull(request.getTextbookName());
        String chapterName = StrUtil.trimToNull(request.getChapterName());
        // ③ 章节必须依附于教材：只传章节不传教材时，无法确定是哪本书的哪一章
        ThrowUtils.throwIf(chapterName != null && textbookName == null,
                ErrorCode.PARAMS_ERROR, "选择章节时必须同时指定教材");

        // ④ 题量收敛：null / 0 / 超大值都纠正到 [1, 上限]，不因此报错
        int size = QuizQuestionSampler.resolveSize(request.getSize(),
                quizProperties.getDefaultSize(), quizProperties.getMaxSize());

        // ⑤ 三种模式唯一分岔的地方：之后全部合流
        List<Long> questionIds = switch (mode) {
            case SEQUENTIAL -> sampleSequential(userId, textbookName, chapterName, size,
                    // restart 用 Boolean.TRUE.equals 判真：getRestart() 为 null 时按 false 处理
                    Boolean.TRUE.equals(request.getRestart()));
            case WRONG -> sampleWrong(userId, textbookName, chapterName, size);
            case RANDOM -> sampleRandom(textbookName, chapterName, size);
        };

        // ⑥ 按模式给不同提示：用户需要知道「下一步该做什么」
        if (questionIds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, emptyMessage(mode, textbookName, chapterName));
        }

        // ⑦ 按 id 批量取详情（一次查询），再按 questionIds 的顺序重排：
        // selectByIds 不保证顺序，而顺序模式必须保持题序
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
        // ⑧ 题目可能在这几毫秒里被停用/删除：全部失效时按「无题」处理
        ThrowUtils.throwIf(ordered.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "题目已被下线，请重新开始");

        // ⑨ 标记哪些题在错题本里（只给标记，不泄露答案）
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
        // ⑩ 批次 ID 由服务端生成：让前端传等于把「本轮是哪几题」的解释权交出去
        batch.setBatchId(IdWorker.getId());
        batch.setMode(mode.getValue());
        // total 用组装后的真实条数，而不是请求的 size：题量不足时两者不同
        batch.setTotal(questions.size());
        batch.setQuestions(questions);
        return batch;
    }

    /**
     * 顺序模式：本章启用题里排除「已作答」的，按题序取前 size 个
     *
     * 排除「已作答」而不是「已掌握」是三种模式不重叠的关键：答错的题归错题重刷管。
     * restart 开关让刷完的章节可以再刷一遍，不必去清空作答记录。
     *
     * 坑：排除用的是用户全部历史作答，跨批次、跨教材都算数——题目被移动过章节时，
     * 它在别处的作答也会让它不再出现。
     *
     * @param textbookName 教材名称，不能为 null（为空时抛参数错误）
     * @param chapterName 章节名称，不能为 null（为空时抛参数错误）
     * @param restart true 表示「重新刷本章」，忽略排除条件
     * @return 题目 ID 列表，保持题序；不会返回 null，但可能为空
     * @throws BusinessException 教材或章节缺失抛 A0401；本章无题或已刷完抛 A0402
     */
    private List<Long> sampleSequential(long userId, String textbookName, String chapterName,
                                        int size, boolean restart) {
        // ① 顺序模式必须明确刷哪一章：不指定的话「从哪开始」没有答案
        ThrowUtils.throwIf(textbookName == null || chapterName == null,
                ErrorCode.PARAMS_ERROR, "顺序刷题必须选择教材与章节");

        // ② 取本章全部启用题，按题序排好（过滤留给下一步）
        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        // ③ 本章一道题都没有：这是「还没录题」，与「刷完了」是两件事
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "本章还没有题目");
        }
        if (!restart) {
            // ④ 一次查出全部已作答的题再一次剔除，避免逐题查询的 N+1
            Set<Long> answered = answeredQuestionIds(userId);
            candidates = QuizQuestionSampler.excludeAnswered(candidates, answered);
            // ⑤ 剔完为空 = 本章刷完：给出可操作的下一步，而不是只说「没有题目」
            if (candidates.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "本章已全部刷完，可勾选「重新刷本章」再刷一遍");
            }
        }
        // ⑥ 只截断不重排：题序由查询保证，重排会破坏「从第一章往后走」的语义
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    /**
     * 错题模式：待复习的错题，最久没复习的先来
     *
     * 过滤与排序交给 QuizWrongService：「什么算待复习」「哪些题可见」是错题本的状态规则，
     * 不该在刷题服务里重复定义。顺序模式排除已作答，而错题本里的题都是已作答且答错的，
     * 两个候选集合天然不重叠。
     *
     * @param textbookName 教材名称，可为 null（表示不限范围）
     * @param chapterName 章节名称，可为 null（表示不限范围）
     * @return 题目 ID 列表，按「最久没复习」升序；不会返回 null，但可能为空
     * @throws BusinessException 错题本为空时抛 A0402
     */
    private List<Long> sampleWrong(long userId, String textbookName, String chapterName, int size) {
        // ① 过滤与排序都在错题本服务里完成，这里不重复实现
        List<Long> candidates = quizWrongService.listPendingQuestionIds(userId, textbookName, chapterName);
        // ② 空 = 错题本已清空，这是好消息，提示要写清楚
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "错题本已清空，暂时没有要复习的题");
        }
        // ③ 排序已保证，只截断
        return QuizQuestionSampler.takeOrdered(candidates, size);
    }

    /**
     * 随机模式：范围内全部启用题 → 服务端洗牌 → 取前 size 个
     *
     * 既不排除已作答也不看错题本，这两件事分别归顺序模式与错题模式管。
     * 不用 {@code ORDER BY RAND()}：那会对整张题表做随机排序，题量上万时是明显的慢查询；
     * 这里只查 ID 列，数据库做一次索引扫描，再在内存里洗牌截断。
     *
     * @param textbookName 教材名称，可为 null（表示全库范围）
     * @param chapterName 章节名称，可为 null（表示不限章节）
     * @return 洗牌后的题目 ID 列表，长度不超过 size；不会返回 null，但可能为空
     * @throws BusinessException 范围内无题时抛 A0402
     */
    private List<Long> sampleRandom(String textbookName, String chapterName, int size) {
        // ① 不排除已作答、不看错题本：抽查语义，三者职责因此不重叠
        List<Long> candidates = enabledQuestionIdsOf(textbookName, chapterName);
        // ② 区分「题库为空」与「该范围为空」：前者要去录题，后者要改筛选条件
        ThrowUtils.throwIf(candidates.isEmpty(), ErrorCode.NOT_FOUND_ERROR,
                textbookName == null ? "题库还没有题目" : "该范围内暂无题目");
        // ③ 内存洗牌后截断；生产路径不需要可复现，random 传 null
        return QuizQuestionSampler.takeRandom(candidates, size, null);
    }

    // ==================== 提交作答 ====================

    /**
     * 提交作答并由服务端判分（幂等）
     *
     * 判分必须在服务端：题目 VO 里没有答案字段，前端拿不到正确答案，也无法自己算对错。
     * 幂等靠作答表的唯一键（用户 + 批次 + 题目）：重复提交会撞键，此时重读已有记录、
     * 原样返回首次判定并跳过错题本更新（否则会重复累加连对）。
     *
     * 坑：标签校验要先查非法字符与重复，再归一成升序无分隔串——顺序反了非法字符会被静默丢掉；
     * 已停用的题仍可提交，批次已经发给用户了，此时拒绝会让他卡在一道永远答不了的题上。
     *
     * @param request 作答请求，不能为 null；chosenLabels 可带分隔符与小写，但不能含非法字符或重复
     * @return 判定结果，含正确答案、解析与错题本动作；重复提交返回首次判定的快照，action 为 none
     * @throws BusinessException 非成员抛 A0301；题目不存在抛 A0402；标签非法或单选传多个抛 A0401
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizVerdictVO submitAnswer(QuizAnswerRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        // ① 题目不存在（含已逻辑删除）：后续所有判断都依赖这条记录，必须先拦
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        // 只拒绝已删除的题（selectById 已被逻辑删除过滤）。
        // 已停用的题仍可提交：批次已经发给用户了，此时拒绝会让用户卡在一道
        // 永远答不了的题上——停用只应影响后续抽题与错题本展示

        // ② 先查非法字符与重复，再归一化：顺序反了会让非法字符被静默丢弃
        Character invalid = QuizLabelUtils.findInvalidLabel(request.getChosenLabels());
        ThrowUtils.throwIf(invalid != null, ErrorCode.PARAMS_ERROR,
                "答案包含非法字符或重复标签：" + invalid);
        String chosen = QuizLabelUtils.normalize(request.getChosenLabels());
        // 归一后为空说明用户什么都没选：这是「提交」接口，空提交没有意义
        ThrowUtils.throwIf(chosen.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择答案");

        // ③ 标签必须属于该题的选项（防「乱传标签刷判定」）
        List<String> optionLabels = quizOptionCodec.labelsOf(quizOptionCodec.decode(question.getOptions()));
        ThrowUtils.throwIf(!optionLabels.containsAll(QuizLabelUtils.toLabelList(chosen)),
                ErrorCode.PARAMS_ERROR, "所选选项不在本题的选项范围内");

        // ④ 按题型校验标签数
        boolean isSingle = QuizQuestionTypeEnum.SINGLE.getValue() == question.getType();
        if (isSingle) {
            // 单选传 2 个及以上是非法请求（正常 UI 产生不了），不是判错
            ThrowUtils.throwIf(chosen.length() != 1, ErrorCode.PARAMS_ERROR, "单选题只能选择一个选项");
        }
        // 多选只选 1 个是用户的真实选择，正常判错，不拒绝

        // ⑤ 判定：集合相等 == 字符串相等（两侧都已归一化为升序无分隔）
        boolean correct = chosen.equals(question.getCorrectLabels());
        int resultValue = correct
                ? QuizAnswerResultEnum.CORRECT.getValue()
                : QuizAnswerResultEnum.WRONG.getValue();

        // ⑥ 幂等：先插，撞唯一键就重读并原样返回首次判定
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

        // ⑦ 重复提交：返回首次判定，action 固定 none——错题本不能再被推进一次
        if (existing != null) {
            return buildVerdict(userId, question, existing, "none", null);
        }

        // ⑧ 首次提交才更新错题本状态机（跳过不走这里）
        QuizWrongService.Outcome outcome = quizWrongService.applyOutcome(
                userId, question.getId(), correct, quizProperties.getResolveStreak());

        return buildVerdict(userId, question, answer, outcome.action(), outcome.remainingStreak());
    }

    // ==================== 跳过 ====================

    /**
     * 跳过当前题（不判分、不碰错题本）
     *
     * 跳过要单独记一条记录，否则用户刷新回来又会看到它，幂等也没有依据。
     * chosen_labels 存 NULL 而不是空串：「没选」与「选了空」是两件事，
     * 存空串会让跳过与提交了一个空答案在数据上无法区分，统计时还会被当成真实作答。
     * 响应里什么都不返回，否则跳过按钮就成了看答案的捷径。
     *
     * 坑：刻意不调用 quizWrongService.applyOutcome，误调会平白给错题本加记录或推进连对进度，
     * 让「跳过」变成刷消解次数的漏洞。
     *
     * @param request 跳过请求，不能为 null
     * @return 恒为 true（重复跳过也返回 true）
     * @throws BusinessException 非成员抛 A0301；题目不存在抛 A0402；落库失败抛 B0001
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean skipQuestion(QuizSkipRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        long userId = requireActiveMember();

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(request.getQuestionId());
        // ① 题目不存在：连记录都不写，否则会留下指向不存在题目的作答记录
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // ② 组装跳过记录：result=跳过，chosen 为空
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
            // ③ 同一批同一题已处理过（答过或跳过了）：幂等返回成功，不重复写。
            // 不碰错题本——跳过本来就不该有任何错题本副作用
            log.info("跳过撞唯一键（重复提交），按幂等返回 | userId={} | questionId={}",
                    userId, question.getId());
        }
        // ④ 只要记录已存在（本次写入或此前写入）就算成功，前端不必区分两种情况
        return true;
    }

    // ==================== 概览 ====================

    /**
     * 学习概览（作答数、正确率、错题本待复习 / 已消解）
     *
     * 四个数字只需两次查询：作答数与答对数由一条带 {@code SUM(CASE WHEN)} 的聚合查出，
     * 错题本的两个数各查一次（它们自带「题目是否可见」的过滤，无法合并）。
     * 分母为 0 时正确率返回 null 而不是 0：0% 读起来像「全错」，前端应显示「暂无数据」。
     *
     * 坑：正确率必须先把分子转成 double，否则整数除法只剩 0.0 与 1.0 两种结果。
     *
     * @return 概览对象；从未作答时 answeredTotal 与 correctTotal 为 0、accuracy 为 null
     * @throws BusinessException 未登录抛 A0201，非在队成员抛 A0301
     */
    @Override
    public QuizOverviewVO getOverview() {
        // ① 非成员不该看到任何统计数据
        long userId = requireActiveMember();
        // ② 一次聚合同时拿两个数，避免两次查询之间数据变化导致自相矛盾
        int[] counts = countAnsweredAndCorrect(userId);

        QuizOverviewVO vo = new QuizOverviewVO();
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        // ③ 分母为 0 时返回 null：0% 读起来像「全错」，那是错误的信息
        vo.setAccuracy(counts[0] == 0 ? null : (double) counts[1] / counts[0]);
        // ④ 与错题本列表同一套过滤条件，保证概览数字与列表条数一致
        vo.setWrongPending(quizWrongService.countPending(userId));
        vo.setWrongResolved(quizWrongService.countResolved(userId));
        return vo;
    }

    /**
     * 一次查询同时取「累计作答数」与「累计答对数」
     *
     * 两个数字总是一起用，分两次查的话中间可能有新作答写入，导致「作答 100、答对 60」
     * 与正确率对不上。跳过（result=3）被排除在分母外：它不是一次「作答」。
     *
     * 坑：本方法不做成员校验，调用方必须自己先确认 userId 属于当前登录用户；
     * 暴露成接口而不校验，任何人都能查到别人的刷题量。
     *
     * @param userId 用户 ID，调用方负责传入当前登录用户
     * @return 长度 2 的数组：[累计作答数, 累计答对数]；没有作答记录时返回 {0, 0}，不会是 null
     */
    @Override
    public int[] countAnsweredAndCorrect(long userId) {
        // ① 一条查询同时拿总数与正确数：两次 COUNT 是不必要的
        QueryWrapper<StudioQuizAnswer> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(*) AS total",
                        "SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) AS correct_cnt")
                .eq("user_id", userId)
                // ② 跳过不算作答：把跳过算进分母会让正确率虚低
                .in("result", ANSWERED_RESULTS);
        List<Map<String, Object>> rows = this.listMaps(wrapper);
        // ③ 理论上聚合一定有一行，兜住方言差异下的空结果
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        Map<String, Object> row = rows.get(0);
        // ④ 走 asInt 的 Number 分支：COUNT 在部分驱动下返回 BigInteger，不能强转
        return new int[]{asInt(row.get("total")), asInt(row.get("correct_cnt"))};
    }

    /**
     * 按批次取本次作答记录（断网重连后恢复进度用）
     *
     * 批次本身不落库，服务端唯一知道「这一轮做了哪些题」的地方就是作答记录表。
     * 按记录 ID 升序：作答记录是追加写的，ID 顺序即用户的操作顺序，前端照此回放才能还原进度。
     *
     * 坑：返回的是实体而不是 VO，记录里含正确答案快照，不能直接暴露成 C 端接口。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @param batchId 批次 ID；传一个不存在的批次不会报错，只会得到空页
     * @return 分页结果，按记录 ID 升序；没有记录时 records 为空列表
     */
    @Override
    public Page<StudioQuizAnswer> listByBatch(long userId, long batchId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        // ① 两个条件缺一不可：少用户条件会越权，少批次条件会混入其它轮次
        wrapper.eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                // ② ID 升序即操作顺序，前端据此回放进度
                .orderByAsc(StudioQuizAnswer::getId);
        // ③ 批次题量受配置封顶，一次取完即可
        return this.page(new Page<>(1, QuizConstant.MAX_PAGE_SIZE), wrapper);
    }

    // ==================== 私有：组装 ====================

    /**
     * 组装判定结果（含答案与解析——本模块唯一的下发出口）
     *
     * 正确答案只在提交之后出现，QuizVerdictVO 的字段差异就是「答案不下发」这条边界的物理实现。
     * correctLabels 取作答记录里的快照而不是题目当前值：管理员可能在提交之后改了题，
     * 返回首次判定时的答案才符合「提交时看到的那道题」；解析取当前题目的值（记录里没存解析）。
     * 末尾顺带查一次累计统计，让前端不必再调概览接口。
     *
     * @param userId 用户 ID，用于查累计统计
     * @param question 题目实体，提供题目 ID、题型与解析（可为已停用的题）
     * @param answer 作答记录，提供结果、所选标签与正确答案快照；不能为 null
     * @param action 本次对错题本的影响类型，重复提交时固定传 none
     * @param remainingStreak 还差几次消解，仅 progress 时非 null
     * @return 判定结果 VO，永不为 null
     */
    private QuizVerdictVO buildVerdict(long userId, StudioQuizQuestion question, StudioQuizAnswer answer,
                                       String action, Integer remainingStreak) {
        QuizVerdictVO vo = new QuizVerdictVO();
        // ① 题目维度：与作答记录无关，直接取当前题目
        vo.setQuestionId(question.getId());
        vo.setType(question.getType());
        // ② 作答维度：全部取自记录，重复提交时才能返回首次判定的内容
        vo.setResult(answer.getResult());
        vo.setChosenLabels(answer.getChosenLabels());
        // 用记录里的快照，而不是当前题目的答案：断网重试时两者可能已经不同
        // （管理员在这期间改了题），返回首次判定才符合「提交时看到的那道题」
        vo.setCorrectLabels(answer.getCorrectLabels());
        // ③ 解析取当前题目的值：作答记录里没有存解析快照，这是已知取舍
        vo.setAnalysis(question.getAnalysis());
        // ④ 错题本动作由状态机给出，本方法只搬运
        vo.setWrongBookAction(action);
        vo.setRemainingStreak(remainingStreak);

        // ⑤ 顺带回填累计统计：前端不必再调一次概览接口
        int[] counts = countAnsweredAndCorrect(userId);
        vo.setAnsweredTotal(counts[0]);
        vo.setCorrectTotal(counts[1]);
        return vo;
    }

    /**
     * 实体 → C 端题目 VO（丢弃答案与解析）
     *
     * 脱敏出口：QuizQuestionVO 结构上没有答案字段，想泄露也没有地方放。
     * 不用 BeanUtils.copyProperties，因为「复制了哪些字段」必须可见，复制工具会让脱敏边界变模糊。
     *
     * 坑：解析失败时 decode 返回空列表，题目在界面上表现为「没有选项可点」，容易被误判成 VO 转换丢了数据。
     *
     * @param question 题目实体，不能为 null
     * @return C 端题目 VO，结构上没有正确答案与解析字段
     */
    private QuizQuestionVO toQuestionVO(StudioQuizQuestion question) {
        QuizQuestionVO vo = new QuizQuestionVO();
        // ① 逐个赋值而不是 copyProperties：脱敏边界必须肉眼可见
        vo.setId(question.getId());
        vo.setType(question.getType());
        vo.setStem(question.getStem());
        // ② 库里是 JSON 字符串、VO 要列表，类型不一致，必须显式转换
        vo.setOptions(quizOptionCodec.decode(question.getOptions()));
        // ③ 默认 false，调用方按需覆盖，避免漏设时给出错误提示
        vo.setInWrongBook(false);
        return vo;
    }

    /**
     * 取该用户待复习错题的题目 ID（用于给题目打「在错题本里」的标记）
     *
     * 只查待复习的条目：已消解的题在用户眼里就是掌握了，再标记会让人困惑。
     * 只 select questionId 一列，并转成 HashSet——下面的 contains 会在每道题上调用一次。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @return 待复习错题的题目 ID 集合；没有错题时返回空集合
     */
    private Set<Long> pendingWrongQuestionIds(long userId) {
        List<StudioQuizWrong> entries = studioQuizWrongMapper.selectList(
                new LambdaQueryWrapper<StudioQuizWrong>()
                        // ① 只查题目 ID 一列，减少回表
                        .select(StudioQuizWrong::getQuestionId)
                        .eq(StudioQuizWrong::getUserId, userId)
                        // ② 只算待复习：已消解的题在用户眼里已经掌握了
                        .eq(StudioQuizWrong::getStatus, QuizWrongStatusEnum.PENDING.getValue()));
        Set<Long> ids = new HashSet<>(entries.size());
        for (StudioQuizWrong entry : entries) {
            // ③ HashSet 让后续每题的 contains 变成 O(1)
            ids.add(entry.getQuestionId());
        }
        return ids;
    }

    /**
     * 范围内（教材 / 章节，均可空）的启用题 ID，按题序升序
     *
     * 顺序模式与随机模式都需要「范围内全部启用题」，差别只在之后怎么挑。只取 status=0 的题：
     * 停用是可逆的下线，不该被抽到。排序用「题序 + ID」双键：题序可能重复，
     * 单靠题序结果不稳定，两次请求可能给出不同顺序。
     *
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限
     * @return 题目 ID 列表，按题序与 ID 升序；范围内没有题时返回空列表
     */
    private List<Long> enabledQuestionIdsOf(String textbookName, String chapterName) {
        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizQuestion::getId)
                // ② 只抽启用的题：停用是可逆下线，不该被抽到
                .eq(StudioQuizQuestion::getStatus, 0)
                // ③ 非 null 才拼条件，避免「OR IS NULL」导致索引失效
                .eq(textbookName != null, StudioQuizQuestion::getTextbookName, textbookName)
                .eq(chapterName != null, StudioQuizQuestion::getChapterName, chapterName)
                // ④ 与 idx_scope 同向；id 作为最终排序键保证同题序时次序稳定
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                .orderByAsc(StudioQuizQuestion::getId);
        return studioQuizQuestionMapper.selectList(wrapper).stream()
                .map(StudioQuizQuestion::getId)
                .collect(Collectors.toList());
    }

    /**
     * 该用户「已作答（正确或错误）」的题目 ID
     *
     * 顺序模式要排除的是「已作答」而不是「已掌握」：答错的题归错题重刷管，顺序模式只负责覆盖。
     * 跳过（result=3）也不算作答过，否则用户一跳过就等于把它漏掉了。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @return 已作答的题目 ID 集合；没有作答记录时返回空集合
     */
    private Set<Long> answeredQuestionIds(long userId) {
        LambdaQueryWrapper<StudioQuizAnswer> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(StudioQuizAnswer::getQuestionId)
                .eq(StudioQuizAnswer::getUserId, userId)
                // ② 只认正确与错误：跳过不是「作答过」
                .in(StudioQuizAnswer::getResult, ANSWERED_RESULTS);
        // ③ 用本 Service 自己的 baseMapper（父类字段），不必再注入一次同一个 Mapper
        return baseMapper.selectList(wrapper).stream()
                .map(StudioQuizAnswer::getQuestionId)
                .collect(Collectors.toSet());
    }

    /**
     * 按（用户 + 批次 + 题目）查已有作答记录
     *
     * 三个条件正好对应唯一键的三列，缺一不可：少用户会读到别人的记录，少批次会把上一轮当成这一轮。
     * 用 list 再取第一条而不是 getOne：唯一键被破坏时宁可返回 null，也不要让用户看到 500。
     *
     * @param userId 用户 ID
     * @param batchId 批次 ID
     * @param questionId 题目 ID
     * @return 作答记录；不存在时返回 null，调用方必须判空
     */
    private StudioQuizAnswer findAnswer(long userId, long batchId, long questionId) {
        List<StudioQuizAnswer> list = this.list(new LambdaQueryWrapper<StudioQuizAnswer>()
                .eq(StudioQuizAnswer::getUserId, userId)
                .eq(StudioQuizAnswer::getBatchId, batchId)
                .eq(StudioQuizAnswer::getQuestionId, questionId));
        // ② 查不到时返回 null：调用方据此决定走正常流程还是幂等返回
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 对外暴露的成员校验：校验当前登录用户是在队成员，并返回其用户 ID
     *
     * 教材/章节树的数据由 QuizQuestionService 提供，但准入规则属于本服务。
     * 在 Controller 里复制一遍「查成员表」的逻辑，两份规则迟早漂移。
     * 不缓存结果：成员状态可能在会话期间被改动，缓存会让刚被移出的人还能继续刷题。
     *
     * 坑：每次调用都会查一次成员表，只应在请求入口调用一次，把返回的 userId 往下传。
     *
     * @return 当前登录账号 ID
     * @throws BusinessException 未登录抛 A0201，非在队成员抛 A0301
     */
    @Override
    public long requireActiveMemberId() {
        return requireActiveMember();
    }

    /**
     * 校验当前登录用户是在队成员，返回其账号 ID（内部实现）
     *
     * 准入的判定权威是 studio_member 表而不是账号表上的角色字段：角色是权限标签，
     * 成员档案才是业务事实，有人被移出名录但角色没改，按角色放行就绕开了真正的准入规则。
     *
     * @return 当前登录账号 ID
     * @throws BusinessException 未登录抛 A0201，非在队成员抛 A0301
     */
    private long requireActiveMember() {
        // ① 先判登录：避免 getLoginIdAsLong 在未登录时抛出难以识别的异常
        ThrowUtils.throwIf(!StpUtil.isLogin(), ErrorCode.NOT_LOGIN_ERROR, "未登录");
        long userId = StpUtil.getLoginIdAsLong();
        // ② 判定权威是成员档案，不是角色标签：两者可能不同步
        ThrowUtils.throwIf(studioMemberService.getActiveMemberByUserId(userId) == null,
                ErrorCode.NO_AUTH_ERROR, "仅工作室成员可以使用刷题功能");
        // ③ 返回账号 ID，供后续按用户维度查询
        return userId;
    }

    /**
     * 「没有题」时的提示文案
     *
     * 三种模式没有题的原因完全不同，提示也必须不同：统一回一句「没有题目」等于什么都没说，
     * 用户不知道该去选章节、该去错题本，还是该找管理员录题。
     *
     * @param mode 刷题模式，不能为 null（switch 遇到 null 会抛 NPE）
     * @param textbookName 教材名称，用于区分「题库为空」与「该范围为空」
     * @param chapterName 章节名称，用于判断顺序模式是否漏选了章节
     * @return 面向用户的中文提示，不含技术细节
     */
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

    /**
     * 聚合结果的数值转 int
     *
     * COUNT 与 SUM 的返回类型随驱动而变（Long / BigInteger / BigDecimal），直接强转 Integer 会抛
     * ClassCastException，且只在特定环境出现。非数值一律返回 0，会把「查询出错」伪装成「没有数据」，
     * 因此只适合统计展示。
     *
     * @param value 聚合查询返回的原始值，可为 null 或任意类型
     * @return 转换后的 int；非 Number 类型返回 0
     */
    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

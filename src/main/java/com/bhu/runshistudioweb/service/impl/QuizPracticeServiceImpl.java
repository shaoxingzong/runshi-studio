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
 * 准入以 studio_member 表为准（与考勤模块完全同源）：
 * 每个公开方法的第一步都是 assertIsActiveMember，
 * 非成员抛 A0301。连教材列表都不给——他没有刷题资格。
 *
 * 判分只在这里发生：submitAnswer 是唯一知道
 * 「正确答案是什么」的地方，且它只在提交之后把答案回给调用方。
 * 出题接口（ startBatch）走的是 QuizQuestionVO，
 * 那个类结构上就没有答案字段——这不是「记得删」，是「根本没有」。
 *
 * 三种模式的职责不重叠（见 QuizQuestionSampler 的类注释）：
 * 顺序负责「覆盖」、错题负责「消灭」、随机负责「抽查」。
 * 因此顺序模式排除的是「已作答」而不是「已掌握」——答错的题归错题重刷管，
 * 在两个入口都出现会让用户困惑「怎么又是这道」。
 *
 * 一个刻意的取舍：不校验 questionId 属于 batchId。
 * 服务端不持久化批次内容（题目不含答案，整批交给前端即可），
 * 因此无法校验。最坏影响是用户给自己刷出脏的作答记录，不涉及越权或他人数据。
 * 若将来要支持「历史成绩单」，才需要引入会话表。
 *
 * 幂等的实现方式值得单独说明：作答表上有「用户 + 批次 + 题目」的唯一键，
 * 重复提交会撞键，此时重读已有记录、原样返回首次判定。幂等（重复调用结果一致）
 * 在这里不是可选优化——断网重试与双击是移动端的常态，把重试当成错误
 * 会让用户在一道已经答过的题上反复看到红色提示。
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
     * <p>为什么这样设计：这个集合的边界就是三种模式「不重叠」的关键。
     * 只放正确与错误两种结果，跳过不在其中——跳过不是「作答过」，
     * 顺序模式下跳过过的题下次还应该出现，否则用户一跳过就等于把它漏掉了。
     * 也不放「已掌握」：掌握与否由错题本判断，顺序模式只关心「有没有走过一遍」。
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
     * <p>为什么这样设计：三种模式的选题策略必须不重叠，否则用户会在两个入口看到同一批题。
     * 顺序模式排除「已作答」的题（答错的题留给错题本），错题模式只出「待复习」的题，
     * 随机模式在范围内随机抽。三者各自走不同的查询条件，本方法只负责按模式分派，
     * 具体的过滤与排序留在各自的私有方法里——这样每段代码的职责都只有一件事。
     * 另一个关键取舍是批次 ID 由服务端生成：如果让前端传，等于把「本轮是哪几题」的
     * 解释权交出去，前端就能伪造一个批次去重放别的题的判定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先做成员准入：非成员连教材列表都不给，更不该拿到题目，
     *     这个校验必须排在任何查询之前，否则越权用户会先消耗一次数据库查询；</li>
     *     <li>② 解析并校验模式：模式是闭集，非法值要立刻报错而不是默认成某个模式，
     *     否则用户以为在刷错题、实际在刷顺序；</li>
     *     <li>③ 归一化教材/章节名并校验「章节必须依附教材」：只传章节无法定位是哪本书的哪一章；</li>
     *     <li>④ 收敛题量：前端传 null / 0 / 超大值都不该让请求失败，统一走 resolveSize；</li>
     *     <li>⑤ 按模式分派取候选 ID：这是三种模式唯一分岔的地方，
     *     分派之后再合流，后面的组装逻辑对三种模式完全一致；</li>
     *     <li>⑥ 候选为空时按模式给出不同的提示：用户需要知道「下一步该做什么」，
     *     统一回一句「没有题目」等于什么都没说；</li>
     *     <li>⑦ 按 ID 批量取详情并按原顺序重排：selectByIds 不保证返回顺序，
     *     而顺序模式必须保持题序，这一步不能省；</li>
     *     <li>⑧ 题目可能在这几毫秒里被停用/删除，全部失效时按「无题」处理；</li>
     *     <li>⑨ 标记哪些题在错题本里：只给布尔标记，不带答案，也不带错题本条目 ID；</li>
     *     <li>⑩ 组装批次 VO 并生成批次 ID。</li>
     * </ol>
     *
     * <p>容易踩的坑：批次本身不落库。题目不含答案，整批交给前端持有即可，
     * 服务端只需要 batchId 做幂等。代价是换设备不能续答——刷题不是考试，
     * 重开的成本为 0。但如果将来要做「历史成绩单」，就必须引入会话表，
     * 否则服务端无法回答「这个 batchId 当时包含哪几题」。
     *
     * @param request 开批请求（模式、教材、章节、题量、是否重新刷本章），不能为 null；
     *                顺序模式必须同时给教材与章节，其余模式可只给范围或完全不给
     * @return 批次（含服务端生成的 batchId 与题目列表，题目结构上没有答案字段）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员时抛 A0301；
     *         模式非法、章节缺教材时抛 A0401；本章刷完、错题本为空、范围内无题时抛 A0402
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
     * 顺序模式：本章启用题里，排除「已作答」的，按题序取前 size 个
     *
     * <p>为什么这样设计：顺序模式负责「覆盖」——从本章第一题往后走一遍，走过的跳过。
     * 排除的口径是「已作答」而不是「已掌握」，这是三种模式不重叠的关键：
     * 答错的题不会在这里反复出现，它们归错题重刷管，那个模式负责「消灭」。
     * 若这里改成排除「已掌握」，答错又没掌握的题会同时出现在两个入口，
     * 用户会困惑「怎么又是这道」；若什么都不排除，章节永远刷不完。
     * 另外提供 restart 开关：本章全刷完之后，用户可能想再刷一遍，
     * 此时忽略排除条件即可，不必让他去清空作答记录。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先校验教材与章节必填：顺序模式的语义依赖「从哪一章开始」，
     *     不指定的话这个问题没有答案，必须比其他两种模式更严格；</li>
     *     <li>② 取本章全部启用题并按题序排序：这一步不做任何过滤，
     *     保持「章节全貌」的语义；</li>
     *     <li>③ 本章没有题时直接报错：这不是「刷完了」而是「还没录题」，
     *     提示要区分开，否则管理员会以为数据丢了；</li>
     *     <li>④ 非 restart 时排除已作答：先取已作答集合再一次剔除，
     *     而不是逐题去查「这题答过没」——那是 N+1；</li>
     *     <li>⑤ 排除后为空说明本章刷完：给出「可勾选重新刷本章」的具体指引，
     *     而不是只说「没有题目」；</li>
     *     <li>⑥ 按题序截断到 size：排序已由查询完成，这里只截断，不重排。</li>
     * </ol>
     *
     * <p>容易踩的坑：排除用的是「用户全部已作答的题」，不是「本批次的题」。
     * 也就是说跨批次、跨教材的作答都算数——这符合「走过的跳过」的语义，
     * 但意味着用户在别的章节答过同一道题（如果题目被移动过章节），这道题也不会再出现。
     *
     * @param userId 当前用户 ID，用于查「已作答」集合
     * @param textbookName 教材名称，不能为 null（为空时抛参数错误）
     * @param chapterName 章节名称，不能为 null（为空时抛参数错误）
     * @param size 本轮题量，已由调用方收敛到合法区间
     * @param restart true 表示「重新刷本章」，忽略排除条件
     * @return 题目 ID 列表，保持题序；不会返回 null，但可能为空（调用方会转成业务异常）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 教材或章节缺失时抛 A0401；
     *         本章无题、或已全部刷完时抛 A0402
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
     * <p>为什么这样设计：错题模式负责「消灭」——它只出待复习的题，且按「最久没复习」
     * 排序。这两个约束让它与顺序模式严格分开：顺序模式排除「已作答」，
     * 而错题本里的题恰恰都是「已作答且答错」的，所以两个模式的候选集合天然不重叠，
     * 用户不会在两个入口看到同一批题。排序交给 QuizWrongService，
     * 因为「什么算待复习」「哪些题可见」是错题本的状态规则，不该在刷题服务里重复定义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接从错题本服务取候选：过滤（待复习、题目未停用未删除）与排序
     *     （lastWrongAt 升序）都在那边完成，这里不重复实现；</li>
     *     <li>② 候选为空说明错题本已清空：这是好消息，提示文案要写清楚，
     *     否则用户会以为功能坏了；</li>
     *     <li>③ 按顺序截断：排序已由错题本服务保证，这里只取前 size 个。</li>
     * </ol>
     *
     * <p>容易踩的坑：本模式不校验教材与章节必填。用户可能只想复习某一章，
     * 也可能想复习全部错题，两种都是合理诉求，因此这两个参数在这里是「可选过滤器」
     * 而不是「必填项」——与顺序模式刚好相反，这个差异容易看漏。
     *
     * @param userId 当前用户 ID，用于取他自己的错题本
     * @param textbookName 教材名称，可为 null（表示不限范围）
     * @param chapterName 章节名称，可为 null（表示不限范围）
     * @param size 本轮题量，已由调用方收敛到合法区间
     * @return 题目 ID 列表，按「最久没复习」升序；不会返回 null，但可能为空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 错题本为空时抛 A0402
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
     * <p>为什么这样设计：随机模式负责「抽查」，所以它既不排除已作答的题，
     * 也不看错题本——这两件事分别归顺序模式与错题模式管，三者职责因此不重叠。
     * 用户想「随便来几道」时，做过与没做过都应该有机会出现。
     * 至于为什么不用 `ORDER BY RAND()`：那会对整张题表做一次随机排序，
     * 题量上万时是明显的慢查询。这里只查 ID 列（覆盖索引，几千个 long 在内存里可忽略），
     * 再在内存里洗牌截断，数据库只做一次索引扫描。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 取范围内全部启用题：不做任何排除，这是「抽查」语义的体现；</li>
     *     <li>② 为空时按「有没有指定教材」给不同提示：题库整体为空与某个范围为空
     *     是两种不同的状况，管理员需要据此判断是去录题还是去改范围；</li>
     *     <li>③ 洗牌后截断：random 传 null 表示走默认随机源，生产路径不需要可复现。</li>
     * </ol>
     *
     * <p>容易踩的坑：随机模式不排除已作答的题，因此用户可能连续两轮抽到同一道题。
     * 这是随机抽样的正常结果，不是 bug；如果产品上要求「本轮不重复」，那是「一轮内不重复」
     * （洗牌已保证），而不是「跨轮不重复」——跨轮去重要额外记录历史，代价与收益不成比例。
     *
     * @param textbookName 教材名称，可为 null（表示全库范围）
     * @param chapterName 章节名称，可为 null（表示不限章节）
     * @param size 本轮题量，已由调用方收敛到合法区间
     * @return 洗牌后的题目 ID 列表，长度不超过 size；不会返回 null，但可能为空
     * @throws com.bhu.runshistudioweb.exception.BusinessException 范围内无题时抛 A0402
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
     * <p>为什么这样设计：判分必须在服务端，因为题目 VO 里根本没有答案字段，前端拿不到
     * 正确答案，也无法自己算对错。这是「展示数据与判定数据物理分离」的落点——
     * 选项 JSON 只存标签与正文，正确答案单独存一列，出题接口返回的 VO 结构上就没有
     * 答案字段，所以「不漏答案」不是靠记得删，而是根本没有地方放。
     * 另一个设计点是幂等（重复调用结果一致）：靠作答表的唯一键（用户 + 批次 + 题目）实现，
     * 重复提交（断网重试、双击）会撞键，此时重读已有记录、原样返回首次判定，
     * 而不是报错——让用户看到「本题已作答」的红条，比看到判定结果更糟。
     * 标签校验也是刻意加的：不校验「标签属于本题选项」的话，用户可以传一堆不存在的标签，
     * 虽然判不对分，但会把脏数据写进作答记录，也让「所选」失去可解释性。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 准入与题目存在性校验：题目不存在直接报错，这是后续所有判断的前提；</li>
     *     <li>② 校验标签合法并规范化：先查非法字符与重复，再归一成升序无分隔串。
     *     这个顺序不能反——先归一的话非法字符会被静默丢掉，用户永远不知道自己传错了；</li>
     *     <li>③ 校验标签属于本题选项：防「乱传标签刷判定」，也让作答记录里的
     *     chosen_labels 始终可解释；</li>
     *     <li>④ 按题型校验标签数：单选传多个是非法请求（正常界面产生不了），
     *     多选只选一个是用户的真实选择，正常判错而不拒绝——这两种情况必须区分开；</li>
     *     <li>⑤ 判定：两侧都是「大写 + 去重 + 升序 + 无分隔」，因此集合相等就是字符串相等，
     *     一次 equals 即可，不必写集合运算；</li>
     *     <li>⑥ 先插作答记录：插入成功才说明「这次提交是有效的」，之后的错题本更新
     *     才有意义；撞唯一键说明重复提交，转去读已有记录；</li>
     *     <li>⑦ 重复提交时按首次判定返回，且不更新错题本（否则会重复累加连对）；</li>
     *     <li>⑧ 首次提交才更新错题本状态机：跳过不走这里，所以错题本不会因跳过而变化。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里不校验 questionId 是否属于 batchId。服务端不持久化批次内容
     * （题目不含答案，整批交给前端即可），因此无法校验。最坏影响是用户给自己刷出脏的
     * 作答记录，不涉及越权或他人数据。若将来要支持「历史成绩单」，才需要引入会话表。
     * 另一个坑是「已停用的题仍可提交」：批次已经发给用户了，此时拒绝会让他卡在一道
     * 永远答不了的题上，停用只应影响后续抽题与错题本展示。
     *
     * @param request 作答请求（批次 ID、题目 ID、所选标签），不能为 null；
     *                chosenLabels 允许带分隔符与小写，但不能含非法字符或重复标签
     * @return 判定结果，含本题的正确答案、解析与错题本动作；
     *         重复提交时返回的是首次判定的快照，action 固定为 none
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员时抛 A0301；
     *         题目不存在时抛 A0402；标签非法、未选、不属于本题选项、单选传多个时抛 A0401
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
     * <p>为什么这样设计：跳过要单独记一条记录，而不是「什么都不做」。两个原因：
     * 一是断点续答需要区分「这题我处理过了（跳过）」与「还没轮到」，
     * 不记录的话用户刷新回来又会看到它；二是幂等需要依据——重复点跳过时靠唯一键
     * 判断「已经处理过」，直接返回成功即可。
     * 另一个关键点是 chosen_labels 存 NULL 而不是空字符串：「没选」与「选了空」
     * 是两件事，全局约定空值一律存 NULL。存空串会让「跳过」与「提交了一个空答案」
     * 在数据上无法区分，统计时也会把空串当成一次真实的作答。
     * 最后，响应里什么都不返回（只有布尔）——否则跳过按钮就成了看答案的捷径。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 准入与题目存在性校验：题目不存在时连记录都不该写，否则会留下
     *     一条指向不存在题目的作答记录；</li>
     *     <li>② 组装跳过记录：chosenLabels 显式设为 null，result 记为「跳过」，
     *     correctLabels 仍存快照（便于事后知道当时这道题的正确答案是什么）；</li>
     *     <li>③ 插入并兜住唯一键冲突：冲突说明同一批同一题已经处理过（答过或跳过了），
     *     按幂等返回成功，不重复写；</li>
     *     <li>④ 恒返回 true：跳过这个动作只要记录落库（或本来就已经记录过）就算成功，
     *     不需要让前端区分这两种情况。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法刻意不调用 quizWrongService.applyOutcome。
     * 跳过既不算答错也不算答对，如果误调，会平白给错题本加一条记录，
     * 或者推进连对进度——这会让「跳过」变成刷消解次数的漏洞。
     * 另外跳过记录计入「已处理」，但不计入正确率的分母（统计只认正确与错误）。
     *
     * @param request 跳过请求（批次 ID、题目 ID），不能为 null；chosenLabels 不参与本方法
     * @return 恒为 true（重复跳过也返回 true）；失败只会以异常形式出现
     * @throws com.bhu.runshistudioweb.exception.BusinessException 非成员时抛 A0301；
     *         题目不存在时抛 A0402；记录落库失败时抛 B0001
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
     * <p>为什么这样设计：四个数字看起来要查四次，实际只需要两次——作答数与答对数
     * 由一条带 SUM(CASE WHEN) 的聚合查出来，错题本的两个数由错题服务各查一次
     * （它们各自带「题目是否可见」的过滤，无法与作答统计合并）。
     * 另一个刻意的取舍是：分母为 0 时正确率返回 null 而不是 0。
     * 0% 读起来像「全错」，那是错误的信息；前端应当显示「暂无数据」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先做成员准入：非成员不该看到任何统计数据；</li>
     *     <li>② 一次查询拿到作答总数与答对数：两次 COUNT 是不必要的，
     *     而且两次查询之间可能有新作答，两个数字会互相矛盾；</li>
     *     <li>③ 正确率按分母是否为 0 分两种：为 0 时给 null，避免把「没做过」
     *     显示成「全错」；</li>
     *     <li>④ 错题本的两个数分别来自待复习计数与已消解计数，
     *     它们与列表接口用同一套过滤条件，保证「概览数字」与「点进去看到的条数」一致。</li>
     * </ol>
     *
     * <p>容易踩的坑：正确率用 double 相除，必须先把分子转成 double
     * （(double) counts[1] / counts[0]），否则会先做整数除法得到 0 或 1，
     * 再转成 double 就只剩 0.0 与 1.0 两种结果。
     *
     * @return 概览对象；从未作答时 answeredTotal 与 correctTotal 为 0、accuracy 为 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛 A0201，
     *         非在队成员时抛 A0301
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
     * <p>为什么这样设计：这两个数字总是一起用（概览与判定结果回填都要），
     * 分成两个方法会让调用方查两次，而两次查询之间可能有新作答写入，
     * 于是「作答 100、答对 60」与「正确率 61%」可能对不上。
     * 用一条 `COUNT(*) + SUM(CASE WHEN result = 1 ...)` 的聚合，两个数字来自同一行，
     * 天然一致。跳过（result=3）被排除在分母之外：把跳过算进正确率会让它虚低，
     * 而跳过并不是一次「作答」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用字符串列名拼聚合：Lambda 版表达不了 SUM(CASE WHEN)，
     *     列名全是硬编码常量，不存在拼接用户输入的注入面；</li>
     *     <li>② 同时限制用户与结果类型：少用户条件会统计到全站数据，
     *     少结果条件会把跳过算进分母；</li>
     *     <li>③ 聚合查询总会返回一行，但驱动或方言差异下仍可能给空列表，兜一下；</li>
     *     <li>④ 按列名取值：COUNT 在不同驱动下可能返回 BigInteger，
     *     因此统一走 asInt 的 Number 分支，不能直接强转 Integer。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有做成员校验，它是纯统计工具，调用方（概览、判分回填）
     * 必须自己先确认 userId 属于当前登录用户。若把它暴露成接口而不校验，
     * 任何人都能查到别人的刷题量。
     *
     * @param userId 用户 ID；传错会统计到别人的数据，调用方负责传入当前登录用户
     * @return 长度 2 的数组：[累计作答数, 累计答对数]；没有作答记录时返回 {0, 0}，
     *         不会是 null
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
     * <p>为什么这样设计：批次本身不落库，服务端唯一知道「这一轮做了哪些题」的地方
     * 就是作答记录表。用户刷新页面或断网重连后，前端拿着 batchId 来查一次，
     * 就能知道哪些题已经处理过、分别是什么结果，从而把进度条恢复到正确位置。
     * 查询按记录 ID 升序：作答记录是追加写的，ID 顺序即用户的操作顺序，
     * 前端按这个顺序回放才能还原「第几题做到哪」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 同时按用户与批次过滤：只按批次过滤会让别人拿到这一批的记录，
     *     只按用户过滤会混进其它批次的记录；</li>
     *     <li>② 按 ID 升序：ID 自增，顺序即操作顺序；</li>
     *     <li>③ 分页取第一页、页大小为上限值：一个批次的题量受配置封顶，
     *     一次取完即可，不必真的做翻页交互。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是实体而不是 VO。作答记录里含有正确答案快照，
     * 因此本方法不能直接暴露成 C 端接口——它只用于内部排查与恢复进度，
     * 真要对外必须先转成脱敏的 VO。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @param batchId 批次 ID；传一个不存在的批次不会报错，只会得到空页
     * @return 分页结果，按记录 ID 升序；没有记录时 records 为空列表（不是 null）
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
     * <p>为什么这样设计：正确答案与解析只在「提交作答之后」出现，而且只针对刚提交的
     * 那一题。出题接口走的是 QuizQuestionVO，那个类结构上没有答案字段；
     * 本方法返回的 QuizVerdictVO 才有——两个 VO 的字段差异就是「答案不下发」
     * 这条安全边界的物理实现。另一个关键点是：correctLabels 取的是作答记录里的快照，
     * 而不是当前题目上的值。管理员可能在用户提交之后改了题，
     * 此时返回首次判定时的答案才符合「提交时看到的那道题」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 填题目维度的字段（题目 ID、题型）：这些从题目实体取，与作答记录无关；</li>
     *     <li>② 填作答维度的字段（结果、所选、正确答案快照）：全部取自记录，
     *     保证重复提交时返回的仍是首次判定的内容；</li>
     *     <li>③ 填解析：解析取当前题目的值而不是快照（记录里没有存解析），
     *     这是已知的取舍——管理员改解析后，重复提交看到的是新解析；</li>
     *     <li>④ 填错题本动作与剩余连对次数：这两个值由状态机给出，本方法只负责搬运；</li>
     *     <li>⑤ 最后再查一次累计统计：让前端拿到判定结果后不必再调一次概览接口，
     *     少一次往返，也少一次「两个接口数据不一致」的可能。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的统计查询发生在同一次请求里，而作答记录的插入也在同一个事务中，
     * 因此读到的数字包含刚刚这一次作答——这正是期望行为。但如果把本方法抽出去
     * 在事务提交之后调用，统计就会读到已提交的数据，两者结果虽然相同，
     * 时间点却不同，调试时容易被误导。
     *
     * @param userId 用户 ID，用于查累计统计
     * @param question 题目实体，提供题目 ID、题型与解析（可为已停用的题）
     * @param answer 作答记录，提供结果、所选标签与正确答案快照；不能为 null
     * @param action 本次对错题本的影响类型，重复提交时固定传 none
     * @param remainingStreak 还差几次消解，仅 progress 时非 null，其余情况传 null
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
     * <p>为什么这样设计：这是本模块的脱敏出口。QuizQuestionVO 结构上没有答案字段，
     * 因此这里「不需要记得删」——想泄露也没有地方放。这比「复制实体后手动置空
     * 几个字段」可靠得多：后者一旦新增一个字段而忘记置空，答案就会跟着返回出去，
     * 而这种疏漏不会报错，只能靠人工审查发现。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 逐个赋题目维度的字段：这里刻意不用 BeanUtils.copyProperties，
     *     因为「复制了哪些字段」必须是可见的，复制工具会让脱敏边界变得模糊；</li>
     *     <li>② 选项走 decode 解析成列表：库里是 JSON 字符串，VO 要的是列表，
     *     类型不一致，复制工具也会静默跳过这个属性；</li>
     *     <li>③ inWrongBook 先给 false，由调用方按需覆盖：VO 不该依赖
     *     「调用方一定会设置」这种隐含约定，默认值能让漏设时至少是安全的。</li>
     * </ol>
     *
     * <p>容易踩的坑：解析失败时 decode 会返回空列表，此时题目在界面上表现为
     * 「没有选项可点」。这不是本方法的问题，但排查时容易误以为是 VO 转换丢了数据。
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
     * <p>为什么这样设计：出题时前端需要知道「这题你之前错过」，好在题目上显示一个标记。
     * 只查待复习的条目（已消解的不算），因为已消解的题在用户眼里就是「掌握了」，
     * 再标一个「在错题本里」会让人困惑。只查 questionId 一列：这里只需要判断
     * 「在不在集合里」，不需要条目内容，少查列能减少回表。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 只 select 题目 ID 列：标记判断只需要集合成员关系；</li>
     *     <li>② 按用户与待复习状态过滤：少状态条件会把已消解的题也标上；</li>
     *     <li>③ 转成 HashSet：下面的 contains 会在每道题上调用一次，
     *     List 的线性查找会让「出 20 题」变成 20 × 错题数的比较。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里没有叠加「题目是否可见」的过滤，与错题本列表的口径略有不同。
     * 影响是：一道题被停用后，它仍可能被标记为「在错题本里」——但停用的题本来
     * 就不会被抽到（抽题只取 status=0），因此这个差异实际不可见。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @return 待复习错题的题目 ID 集合；没有错题时返回空集合（不是 null）
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
     * <p>为什么这样设计：顺序模式与随机模式都需要「范围内全部启用题」，
     * 两个模式的差别只在「之后怎么挑」，因此这一步被抽成共用方法。
     * 只取 status=0 的题：停用是可逆的下线，它不该被抽到。
     * 排序用「题序 + ID」双键：题序可能重复（导入时按起始序号递增，
     * 手工录入时可能都填 0），单靠题序排序结果不稳定，翻页或两次请求可能给出不同顺序，
     * 加上 ID 兜底才能保证「同一章每次刷到的顺序一致」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 只 select ID 列：抽题只需要 ID，后面再按 ID 批量取详情，
     *     这样「取候选」与「取详情」是两次明确的查询，各自可以走索引；</li>
     *     <li>② 状态条件写死为启用：少这个条件会把停用题抽给用户；</li>
     *     <li>③ 教材与章节按「非 null 才拼」：两个都不传就是全库范围（随机模式允许）；</li>
     *     <li>④ 题序 + ID 双键升序：保证顺序稳定且与索引同向。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的是 List 而不是 Set，因为顺序模式必须保持题序；
     * 但随机模式其实不关心顺序，它拿到之后会整体洗牌——共用同一个方法是为了
     * 「范围内有哪些题」这个口径只有一份定义，而不是为了省代码。
     *
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限
     * @return 题目 ID 列表，按题序与 ID 升序；范围内没有题时返回空列表（不是 null）
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
     * <p>为什么这样设计：顺序模式要排除的是「已作答」而不是「已掌握」。
     * 两者差别很大：答错的题虽然「已作答」，但并没有掌握，它归错题重刷管；
     * 顺序模式只负责覆盖，不负责消灭。若这里改成排除「已掌握」，
     * 那些答错又没掌握的题会同时出现在顺序与错题两个入口，用户会困惑「怎么又是这道」。
     * 跳过（result=3）也不算「作答过」：跳过只是「现在不想做」，
     * 它不该让这道题从顺序模式里消失，否则用户一跳过就等于把它漏掉了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 只 select 题目 ID 列：只需要集合成员关系，不需要记录内容；</li>
     *     <li>② 结果类型限定为正确与错误两种：ANSWERED_RESULTS 常量就是这两个值，
     *     跳过不在其中；</li>
     *     <li>③ 用本 Service 自己的 baseMapper：父类 ServiceImpl 已经持有
     *     StudioQuizAnswerMapper，不必再注入一次同一个 Mapper。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法查的是「用户全部历史作答」，不限于当前教材章节。
     * 这是刻意的：顺序模式的语义是「走过的跳过」，跨章节的作答也算走过。
     * 代价是题量很大时这个集合会变大，因此只查一列、只在本章有候选时才调用。
     *
     * @param userId 用户 ID，必须是当前登录用户
     * @return 已作答的题目 ID 集合；没有作答记录时返回空集合（不是 null）
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
     * <p>为什么这样设计：撞唯一键之后要「原样返回首次判定」，因此必须把那条记录读出来。
     * 三个条件正好对应唯一键的三个列，缺一不可：少用户会读到别人的记录，
     * 少批次会把上一轮的记录当成这一轮的，少题目则完全定位不到具体哪一题。
     * 用 list 再取第一条而不是 getOne：唯一键保证最多一条，但这里不想在
     * 「唯一键被破坏」时抛异常——那是排查问题，而不是让用户看到 500。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 三个等值条件一起传：与唯一键一一对应，保证最多命中一条；</li>
     *     <li>② 空列表返回 null：调用方用它判断「有没有找到首次判定」，
     *     找不到时按正常流程继续（虽然撞了唯一键却查不到，理论上不可能）。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的记录里含有正确答案快照，属于敏感数据，
     * 不能直接暴露给前端，只能经 buildVerdict 组装后再返回。
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
     * <p>为什么这样设计：教材/章节树的数据由 QuizQuestionService 提供，但准入规则属于
     * 本服务。如果 Controller 只挂登录校验而不做成员校验，一个「只有账号、没有成员档案」
     * 的普通用户就能读到整个题库目录。与其在 Controller 里复制一遍「查成员表」的逻辑
     * （两份规则迟早漂移），不如把这个判定暴露成公开方法，让所有入口共用同一个实现。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 直接转调私有的 requireActiveMember：对外只暴露一个入口，
     *     真正的判定逻辑仍然只有一份；</li>
     *     <li>② 不缓存结果：成员状态可能在会话期间被管理员改动，
     *     缓存会让「刚被移出工作室的人还能继续刷题」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法每次调用都会查一次成员表。在循环里调用它会变成 N 次查询，
     * 因此只应在请求入口调用一次，把返回的 userId 往下传。
     *
     * @return 当前登录账号 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛 A0201，
     *         非在队成员时抛 A0301
     */
    @Override
    public long requireActiveMemberId() {
        return requireActiveMember();
    }

    /**
     * 校验当前登录用户是在队成员，返回其账号 ID（内部实现）
     *
     * <p>为什么这样设计：准入的判定权威是 studio_member 表（用户 ID 命中且状态为在队），
     * 不是账号表上的角色字段。角色是权限标签，成员档案才是业务事实，两者可能不同步
     * （有人被移出名录但角色没改）。若按角色放行，就绕开了真正的准入规则。
     * 这与考勤模块完全同源，两处必须用同一套判定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先判登录：未登录时 getLoginIdAsLong 会抛异常，提前判断能给出明确的错误码；</li>
     *     <li>② 取登录 ID 再查成员档案：两步分开，是为了让「未登录」与「不是成员」
     *     返回不同的错误码——前者该去登录，后者该去找管理员；</li>
     *     <li>③ 档案为空即拒绝：这里不区分「从未加入」与「已退出」，
     *     对用户来说都是「没有资格」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的是用户 ID 而不是成员档案。调用方若需要成员的其它信息
     * （姓名、入队时间），必须自己再查一次，不要指望从这里拿到。
     *
     * @return 当前登录账号 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录时抛 A0201，
     *         非在队成员时抛 A0301
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
     * <p>为什么这样设计：三种模式「没有题」的原因完全不同，提示也必须不同。
     * 顺序模式可能是「没选章节」，也可能是「本章刷完了」；错题模式是「错题本清空」；
     * 随机模式是「题库为空」或「该范围没题」。统一回一句「没有题目」等于什么都没说，
     * 用户不知道该去选章节、还是该去错题本、还是该找管理员录题。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 switch 表达式覆盖三种模式：编译器会强制覆盖所有枚举值，
     *     将来新增模式时不会漏掉文案；</li>
     *     <li>② 顺序模式内部再按「有没有章节」分两种：这是同一种模式下两种不同的原因。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里假设 mode 不为 null。调用方（开批方法）已经校验过模式合法性，
     * 若将来有别处调用，必须自己先判空，否则 switch 会抛 NullPointerException。
     *
     * @param mode 刷题模式，不能为 null
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
     * <p>为什么这样设计：COUNT 与 SUM 的返回类型在不同数据库驱动下并不一致，
     * 可能是 Long、BigInteger 甚至 BigDecimal。直接强转 Integer 会在某些驱动下
     * 抛 ClassCastException，而这种异常只在特定环境出现，本地很难复现。
     * 统一走 Number 接口取 intValue，就能兼容所有数值类型。
     *
     * <p>容易踩的坑：非数值类型（null、字符串）一律返回 0。这会把「查询出错」
     * 伪装成「没有数据」，因此只适合用于统计展示，不要用它做业务判断的依据。
     *
     * @param value 聚合查询返回的原始值，可为 null 或任意类型
     * @return 转换后的 int；非 Number 类型返回 0
     */
    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

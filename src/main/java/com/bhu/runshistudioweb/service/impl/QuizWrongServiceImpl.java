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

    /** 「题目可见」的子查询条件 */
    private static final String VISIBLE_QUESTION_SQL =
            "SELECT id FROM studio_quiz_question WHERE deleted_at = 0 AND status = 0";

    private final StudioQuizQuestionMapper studioQuizQuestionMapper;

    private final StudioQuizAnswerMapper studioQuizAnswerMapper;

    private final QuizOptionCodec quizOptionCodec;

    // 状态机

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak) {
        StudioQuizWrong existing = findByUserAndQuestion(userId, questionId);

        if (!correct) {
            return handleWrong(userId, questionId, existing);
        }
        // 答对只推进连对进度；这里不再查一次库，复用①的结果
        return handleCorrect(existing, resolveStreak);
    }

    /** 答错：首次入本，或把已有条目打回待复习并累加次数 */
    private Outcome handleWrong(long userId, long questionId, StudioQuizWrong existing) {
        // 时间只取一次：两处写入共用同一个时刻，避免同一行记录出现两个不同的「错误时间」
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            StudioQuizWrong wrong = new StudioQuizWrong();
            wrong.setUserId(userId);
            wrong.setQuestionId(questionId);
            // 新条目一定是待复习：做错过才进本，进来就等着复习
            wrong.setStatus(STATUS_PENDING);
            wrong.setWrongCount(1);
            wrong.setCorrectStreak(0);
            wrong.setLastWrongAt(now);
            boolean saved = this.save(wrong);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "记录错题失败");
            return new Outcome("added", null);
        }

        boolean wasResolved = Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus());
        // 新建实体 + setId 做部分更新：updateById 只写非 null 字段，不会误改其它列
        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        update.setStatus(STATUS_PENDING);
        update.setWrongCount(safeInt(existing.getWrongCount()) + 1);
        // 答错即清零连对：连对的前提是「连续答对」，中间断了一次就不能接着算
        update.setCorrectStreak(0);
        update.setLastWrongAt(now);
        this.updateById(update);

        if (wasResolved) {
            LambdaUpdateWrapperHolder.clearResolved(this, existing.getId());
        }
        // 已消解过的条目再次答错，用 reopened 让前端提示「这道题又回来了」
        return new Outcome(wasResolved ? "reopened" : "added", null);
    }

    /** 答对：推进连对进度，达到阈值就消解 */
    private Outcome handleCorrect(StudioQuizWrong existing, int resolveStreak) {
        // 做对的题不进错题本：没有条目就没有任何状态可更新
        if (existing == null) {
            return new Outcome("none", null);
        }
        if (Integer.valueOf(STATUS_RESOLVED).equals(existing.getStatus())) {
            return new Outcome("none", null);
        }

        // 阈值夹到至少 1，防配置写成 0 导致「一次答对就消解」
        int threshold = Math.max(1, resolveStreak);
        int newStreak = safeInt(existing.getCorrectStreak()) + 1;

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(existing.getId());
        update.setCorrectStreak(newStreak);
        // 先加再比：否则「差最后一次」的答对不会触发消解
        if (newStreak >= threshold) {
            // 三个字段同一次 update 写入，避免「已消解却没有消解时间」的中间状态
            update.setStatus(STATUS_RESOLVED);
            update.setResolvedAt(LocalDateTime.now());
            // 自动消解与手动标记用不同的 resolveType，便于事后区分是谁判定掌握的
            update.setResolveType(QuizResolveTypeEnum.AUTO.getValue());
            this.updateById(update);
            return new Outcome("resolved", 0);
        }
        // 未达阈值只推进连对，剩余次数交给前端提示
        this.updateById(update);
        return new Outcome("progress", threshold - newStreak);
    }

    // 查询

    /** 我的错题本分页（只列待复习，不含答案） */
    @Override
    public Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request) {
        // 请求为 null 时兜一个空对象，下面就不必到处判空
        QuizWrongQueryRequest query = request == null ? new QuizWrongQueryRequest() : request;

        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StudioQuizWrong::getUserId, userId)
                // 只列待复习：已消解的条目留在库里是为了保留「什么时候掌握的」，但不再展示
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 过滤停用/删除的题：与 countPending 用同一个条件，否则计数与列表对不上
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)
                .orderByAsc(StudioQuizWrong::getLastWrongAt)
                // 同一时刻入本的多条按 id 兜底排序，保证分页结果稳定（否则翻页可能重复或漏条）
                .orderByAsc(StudioQuizWrong::getId);

        List<Long> scopedQuestionIds = null;
        if (query.getTextbookName() != null || query.getChapterName() != null) {
            scopedQuestionIds = questionIdsOf(query.getTextbookName(), query.getChapterName());
            // 范围内没有题：直接返回空页，避免带着空 IN 条件去查库
            if (scopedQuestionIds.isEmpty()) {
                return new Page<>(1, QuizConstant.DEFAULT_PAGE_SIZE, 0);
            }
            wrapper.in(StudioQuizWrong::getQuestionId, scopedQuestionIds);
        }

        // 分页参数走兜底纠正：页码传错不该让整个查询失败
        Page<StudioQuizWrong> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);
        // 题目信息一次批量查完，避免 N+1
        List<QuizWrongItemVO> records = toItemVOs(entityPage.getRecords());

        // current / size / total 必须一并带过去，否则前端分页控件会显示成空
        Page<QuizWrongItemVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /** 错题详情（含题干、选项、正确答案、解析、上次所选） */
    @Override
    public QuizWrongDetailVO getMyWrongDetail(long userId, long id) {
        // 负数与 0 不可能存在，早点拒绝省一次查询
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        // 不存在与不属于本人返回同一个错误：不暴露「这个 id 存在但不是你的」
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        StudioQuizQuestion question = studioQuizQuestionMapper.selectById(wrong.getQuestionId());
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目已被删除");

        QuizWrongDetailVO detail = new QuizWrongDetailVO();
        // 公共字段（题干、教材章节、次数、时间）先由 fillItem 统一填充
        fillItem(detail, wrong, question);
        // 详情才带答案：能进错题本的前提是「你做错过」
        detail.setOptions(quizOptionCodec.decode(question.getOptions()));
        detail.setCorrectLabels(question.getCorrectLabels());
        detail.setAnalysis(question.getAnalysis());
        // 「上次所选」存在作答表里，错题条目只记状态，所以必须单独查
        detail.setLastChosenLabels(lastWrongChosen(userId, wrong.getQuestionId()));
        return detail;
    }

    /** 手动标记「已掌握」（直接把条目置为已消解） */
    @Override
    public boolean resolveManually(long userId, long id) {
        // 与详情接口同一套入参校验
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "错题 id 不合法");
        StudioQuizWrong wrong = this.getById(id);
        // 归属校验必须在写之前：不校验就能改别人的错题本
        ThrowUtils.throwIf(wrong == null || !Long.valueOf(userId).equals(wrong.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "错题不存在或无权访问");

        if (Integer.valueOf(STATUS_RESOLVED).equals(wrong.getStatus())) {
            return true;
        }

        StudioQuizWrong update = new StudioQuizWrong();
        update.setId(id);
        update.setStatus(STATUS_RESOLVED);
        update.setResolvedAt(LocalDateTime.now());
        // 手动与自动用不同的 resolveType，便于事后区分是谁判定掌握的
        update.setResolveType(QuizResolveTypeEnum.MANUAL.getValue());
        return this.updateById(update);
    }

    /** 待复习错题数（学习概览用） */
    @Override
    public int countPending(long userId) {
        // 用户 + 状态 + 题目可见性，三个条件与列表接口完全一致
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_PENDING)
                // 与列表用同一个过滤条件：否则会出现「计数 9、列表 7」
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    /** 已消解错题数（学习概览用） */
    @Override
    public int countResolved(long userId) {
        return Math.toIntExact(this.count(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getStatus, STATUS_RESOLVED)
                // 与待复习数同一套可见性口径，两个数字才能相加着看
                .inSql(StudioQuizWrong::getQuestionId, VISIBLE_QUESTION_SQL)));
    }

    /** 待复习错题的题目 ID（错题重刷模式的开批来源） */
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
        // 空结果直接给空列表，调用方只需判 isEmpty
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        // 不限范围：省掉一次「查范围内题目」的查询
        if (textbookName == null && chapterName == null) {
            return questionIds;
        }
        // 范围过滤：保留原顺序（最久没复习优先），只剔掉不在范围内的
        List<Long> scoped = questionIdsOf(textbookName, chapterName);
        return questionIds.stream().filter(scoped::contains).toList();
    }

    // 私有

    /** 按「用户 + 题目」查错题条目 */
    private StudioQuizWrong findByUserAndQuestion(long userId, long questionId) {
        return this.getOne(new LambdaQueryWrapper<StudioQuizWrong>()
                .eq(StudioQuizWrong::getUserId, userId)
                .eq(StudioQuizWrong::getQuestionId, questionId));
    }

    /** 取范围内（教材 / 章节，均可空）的题目 ID */
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

    /** 该用户对某题最近一次答错时所选的标签 */
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
        // 没有答错记录：返回 null，前端据此决定是否渲染「上次所选」
        if (answers.isEmpty()) {
            return null;
        }
        return answers.get(0).getChosenLabels();
    }

    /** 错题条目批量转 VO（题目信息一次查完，避免 N+1） */
    private List<QuizWrongItemVO> toItemVOs(List<StudioQuizWrong> entries) {
        List<QuizWrongItemVO> result = new ArrayList<>();
        // 空页直接返回，不必走查询
        if (entries == null || entries.isEmpty()) {
            return result;
        }
        List<Long> questionIds = entries.stream().map(StudioQuizWrong::getQuestionId).toList();
        // 一次查完：查询次数恒为 2，与页大小无关
        Map<Long, StudioQuizQuestion> questionMap = studioQuizQuestionMapper.selectByIds(questionIds).stream()
                .collect(Collectors.toMap(StudioQuizQuestion::getId, q -> q, (a, b) -> a));

        for (StudioQuizWrong entry : entries) {
            StudioQuizQuestion question = questionMap.get(entry.getQuestionId());
            if (question == null) {
                continue;
            }
            QuizWrongItemVO vo = new QuizWrongItemVO();
            // 公共字段与详情共用 fillItem，避免两处赋值逐渐分叉
            fillItem(vo, entry, question);
            result.add(vo);
        }
        return result;
    }

    /** 填充错题条目与题目的公共字段（列表与详情共用） */
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

    /** 分页参数兜底纠正（不抛异常） */
    private Page<StudioQuizWrong> newPageWithDefaults(Long current, Long pageSize) {
        // 负数页码会算出负偏移量，必须纠正成第 1 页
        long page = current == null || current < 1 ? 1L : current;
        // 非法值用默认值，超限值压到上限，防止一次拉全表
        long size = pageSize == null || pageSize < 1 ? QuizConstant.DEFAULT_PAGE_SIZE : pageSize;
        return new Page<>(page, Math.min(size, QuizConstant.MAX_PAGE_SIZE));
    }

    /** 计数列取值兜底：null 按 0 处理 */
    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 「清空消解信息」的小工具 */
    private static final class LambdaUpdateWrapperHolder {

        /** 清空指定错题条目的消解时间与消解方式 */
        private static void clearResolved(QuizWrongServiceImpl service, Long id) {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StudioQuizWrong> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            // 按主键定位，调用方已持有条目实体，不需要再按「用户 + 题目」查
            wrapper.eq(StudioQuizWrong::getId, id)
                    // 两列同时清空，避免「有类型无时间」的矛盾状态
                    .set(StudioQuizWrong::getResolvedAt, null)
                    .set(StudioQuizWrong::getResolveType, null);
            // 传 null 实体：只执行 wrapper 里的 set，不按实体字段更新
            service.baseMapper.update(null, wrapper);
        }

        private LambdaUpdateWrapperHolder() {
        }
    }
}

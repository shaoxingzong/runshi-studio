package com.bhu.runshistudioweb.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bhu.runshistudioweb.constant.QuizConstant;
import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.manager.QuizImportParser;
import com.bhu.runshistudioweb.manager.QuizOptionCodec;
import com.bhu.runshistudioweb.mapper.StudioQuizAnswerMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizQuestionMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizWrongMapper;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterDeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterMoveRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterRenameRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizOptionItem;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionAddRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionImportRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionQueryRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionUpdateRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizTextbookRenameRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizAnswer;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import com.bhu.runshistudioweb.model.enums.OperationTypeEnum;
import com.bhu.runshistudioweb.model.enums.QuizAnswerResultEnum;
import com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizImportResultVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOptionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizTextbookVO;
import com.bhu.runshistudioweb.service.OperationLogService;
import com.bhu.runshistudioweb.service.QuizQuestionService;
import com.bhu.runshistudioweb.utils.QuizLabelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 刷题题库服务实现
 *
 * @author shaoshing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizQuestionServiceImpl extends ServiceImpl<StudioQuizQuestionMapper, StudioQuizQuestion>
        implements QuizQuestionService {

    /** 状态：启用 */
    private static final int STATUS_ENABLED = 0;

    /** 状态：停用 */
    private static final int STATUS_DISABLED = 1;

    /** 默认难度：易（与 DDL 默认值一致） */
    private static final int DEFAULT_DIFFICULTY = 1;

    /** 默认顺序值 */
    private static final int DEFAULT_SEQ = 0;

    private final QuizOptionCodec quizOptionCodec;

    private final StudioQuizAnswerMapper studioQuizAnswerMapper;

    private final StudioQuizWrongMapper studioQuizWrongMapper;

    /** 操作日志（题目与章节的增删改都要留痕） */
    private final OperationLogService operationLogService;

    // C 端：教材 / 章节树

    /** 教材 → 章节树（C 端下拉数据源） */
    @Override
    public List<QuizTextbookVO> listCatalog() {
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq", "COUNT(*) AS cnt")
                // 只统计启用题：用户看到的数字是「这一章有多少题可刷」
                .eq("status", STATUS_ENABLED)
                .groupBy("textbook_name", "chapter_name");

        List<Map<String, Object>> rows = this.listMaps(wrapper);

        Map<String, QuizTextbookVO> textbookMap = new LinkedHashMap<>();
        Map<String, QuizChapterVO> chapterMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String textbookName = asString(row.get("textbook_name"));
            String chapterName = asString(row.get("chapter_name"));
            // 名字为空的行来自脏数据：跳过比让整个目录接口报错更合适
            if (textbookName == null || chapterName == null) {
                continue;
            }
            int textbookSeq = asInt(row.get("textbook_seq"));
            int chapterSeq = asInt(row.get("chapter_seq"));
            int count = asInt(row.get("cnt"));

            // computeIfAbsent：每本教材只建一个对象，多行只做题数累加
            QuizTextbookVO textbook = textbookMap.computeIfAbsent(textbookName, name -> {
                QuizTextbookVO vo = new QuizTextbookVO();
                vo.setName(name);
                vo.setSeq(textbookSeq);
                vo.setQuestionCount(0);
                vo.setChapters(new ArrayList<>());
                return vo;
            });
            // 教材题数 = 各章题数之和，随行的出现逐次累加
            textbook.setQuestionCount(textbook.getQuestionCount() + count);

            // 章节的唯一键是「教材名 + 章节名」：不同教材可以有同名章节
            String chapterKey = textbookName + "\u0000" + chapterName;
            QuizChapterVO chapter = chapterMap.get(chapterKey);
            if (chapter == null) {
                chapter = new QuizChapterVO();
                chapter.setName(chapterName);
                chapter.setSeq(chapterSeq);
                chapter.setQuestionCount(count);
                chapterMap.put(chapterKey, chapter);
                textbook.getChapters().add(chapter);
            } else {
                // 同章多行时累加（GROUP BY 已保证一行，这里是防御性写法）
                chapter.setQuestionCount(chapter.getQuestionCount() + count);
            }
        }

        // 排序值可能为 null，必须用 nullsLast，否则比较器会抛空指针
        List<QuizTextbookVO> result = new ArrayList<>(textbookMap.values());
        result.sort(Comparator.comparing(QuizTextbookVO::getSeq, Comparator.nullsLast(Integer::compareTo))
                // 排序值相同时按名称兜底，保证两次请求的顺序一致
                .thenComparing(QuizTextbookVO::getName));
        for (QuizTextbookVO textbook : result) {
            textbook.getChapters().sort(
                    Comparator.comparing(QuizChapterVO::getSeq, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(QuizChapterVO::getName));
        }
        return result;
    }

    // 管理端：题目读写

    /** 管理端题目列表（含答案、解析、作答数与正确率） */
    @Override
    public Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request) {
        // 不传条件 = 无条件查第一页，是合理用法，兜一个空对象即可
        QuizQuestionQueryRequest query = request == null ? new QuizQuestionQueryRequest() : request;

        // 题型是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
        if (query.getType() != null && QuizQuestionTypeEnum.of(query.getType()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "题型取值非法，合法值为 " + QuizQuestionTypeEnum.valuesText());
        }
        // 状态同样是闭集：只允许 0 与 1，其它值直接报错
        if (query.getStatus() != null && query.getStatus() != STATUS_ENABLED
                && query.getStatus() != STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "状态只能是 0（启用）或 1（停用）");
        }

        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        // 等值条件全部「非 null 才拼」：null 表示不筛选，不能拼成 = null（永远不成立）
        wrapper.eq(query.getTextbookName() != null, StudioQuizQuestion::getTextbookName, query.getTextbookName())
                .eq(query.getChapterName() != null, StudioQuizQuestion::getChapterName, query.getChapterName())
                .eq(query.getType() != null, StudioQuizQuestion::getType, query.getType())
                .eq(query.getStatus() != null, StudioQuizQuestion::getStatus, query.getStatus())
                // 关键词只搜题干：搜解析会让「解析里提过某个词」的题也混进来，管理员难以预期
                .like(StrUtil.isNotBlank(query.getKeyword()), StudioQuizQuestion::getStem, query.getKeyword())
                // 教材 → 章节 → 题序：与 idx_scope 同向，也让「同一章的题连在一起」
                .orderByAsc(StudioQuizQuestion::getTextbookSeq)
                .orderByAsc(StudioQuizQuestion::getTextbookName)
                .orderByAsc(StudioQuizQuestion::getChapterSeq)
                .orderByAsc(StudioQuizQuestion::getChapterName)
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                // ID 作为最终排序键：题序可能重复，没有它分页结果会不稳定
                .orderByAsc(StudioQuizQuestion::getId);

        // 分页在数据库侧完成，不能查全表再在内存里切页
        Page<StudioQuizQuestion> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);

        List<QuizQuestionAdminVO> records = entityPage.getRecords().stream()
                .map(this::toAdminVO)
                .collect(Collectors.toList());
        // 一次 GROUP BY 填完作答数与正确率，查询次数与页大小无关
        fillAnswerStats(records);

        // current / size / total 一并带过去，否则前端分页控件会显示成空
        Page<QuizQuestionAdminVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /** 管理端题目详情（含答案与解析） */
    @Override
    public QuizQuestionAdminVO getQuestionById(long id) {
        // 负数与 0 不可能存在，早点拒绝省一次查询
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        StudioQuizQuestion question = this.getById(id);
        // 逻辑删除的题查不到，给「不存在」而不是空对象，前端才能明确提示
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        QuizQuestionAdminVO vo = toAdminVO(question);
        // 复用批量统计方法：口径与列表完全一致，不会出现两套算法
        fillAnswerStats(List.of(vo));
        return vo;
    }

    /** 新增一道题目：校验通过后以「启用」状态入库，返回新题目的 ID */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addQuestion(QuizQuestionAddRequest request) {
        // @Valid 不覆盖「整个请求体为 null」，不拦会变成空指针
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        List<QuizOptionVO> options = toOptionVOs(request.getOptions());
        NormalizedQuestion normalized = validateAndNormalize(
                request.getType(), request.getStem(), options, request.getCorrectLabels(),
                request.getAnalysis());

        // 组装实体：只有校验后的值才写库，教材/章节名在这里 trim
        StudioQuizQuestion question = new StudioQuizQuestion();
        question.setTextbookName(request.getTextbookName().trim());
        question.setTextbookSeq(defaultIfNull(request.getTextbookSeq(), DEFAULT_SEQ));
        question.setChapterName(request.getChapterName().trim());
        question.setChapterSeq(defaultIfNull(request.getChapterSeq(), DEFAULT_SEQ));
        question.setType(normalized.type().getValue());
        question.setStem(normalized.stem());
        question.setOptions(quizOptionCodec.encode(normalized.options()));
        question.setCorrectLabels(normalized.correctLabels());
        question.setAnalysis(normalized.analysis());
        question.setDifficulty(defaultIfNull(request.getDifficulty(), DEFAULT_DIFFICULTY));
        question.setSource(StrUtil.blankToDefault(request.getSource(), null));
        question.setQuestionSeq(defaultIfNull(request.getQuestionSeq(), DEFAULT_SEQ));
        // 新题一律启用：DTO 里没有 status，需要停用请走 updateStatus
        question.setStatus(STATUS_ENABLED);

        // save 返回 false 说明写库失败，必须显式报错，否则调用方会拿到 ID 为 null 的「成功」
        boolean saved = this.save(question);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "新增题目失败，数据库异常");
        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体

        // 留痕：题库变更会影响所有人的刷题结果，必须能查到「谁加了这道题、答案是哪个」
        operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_ADD, question.getId(), stemSummary(normalized.stem()),
                "教材：" + question.getTextbookName() + "；章节：" + question.getChapterName()
                        + "；题型：" + question.getType() + "；正确答案：" + question.getCorrectLabels());
        return question.getId();
    }

    /** 修改题目（部分更新）：用旧值补齐后整体校验，判定相关变更时重置错题连对进度 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateQuestion(QuizQuestionUpdateRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null,
                ErrorCode.PARAMS_ERROR, "题目 id 不能为空");

        StudioQuizQuestion existing = this.getById(request.getId());
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        int newType = request.getType() == null ? existing.getType() : request.getType();
        String newStem = request.getStem() == null ? existing.getStem() : request.getStem();
        List<QuizOptionVO> newOptions = request.getOptions() == null
                ? quizOptionCodec.decode(existing.getOptions())
                : toOptionVOs(request.getOptions());
        String newCorrect = request.getCorrectLabels() == null
                ? existing.getCorrectLabels() : request.getCorrectLabels();
        // 解析同样要「用旧值补齐」：只改题干时不该把解析清空
        String newAnalysis = request.getAnalysis() == null ? existing.getAnalysis() : request.getAnalysis();

        // 整体校验（与新增共用同一套规则）
        NormalizedQuestion normalized = validateAndNormalize(newType, newStem, newOptions, newCorrect, newAnalysis);

        // 判定相关变更检测：必须在覆盖旧值之前算，否则比较时永远相等
        boolean verdictChanged = isVerdictChanged(existing, normalized);

        StudioQuizQuestion update = new StudioQuizQuestion();
        update.setId(request.getId());
        update.setTextbookName(request.getTextbookName() == null ? null : request.getTextbookName().trim());
        update.setTextbookSeq(request.getTextbookSeq());
        update.setChapterName(request.getChapterName() == null ? null : request.getChapterName().trim());
        update.setChapterSeq(request.getChapterSeq());
        update.setType(normalized.type().getValue());
        update.setStem(normalized.stem());
        update.setOptions(quizOptionCodec.encode(normalized.options()));
        update.setCorrectLabels(normalized.correctLabels());
        update.setAnalysis(normalized.analysis());
        update.setDifficulty(request.getDifficulty());
        // 题源传空串表示清空 → 归一成 null，与解析同样走下面的「显式清空」分支
        update.setSource(StrUtil.trimToNull(request.getSource()));
        update.setQuestionSeq(request.getQuestionSeq());
        update.setStatus(request.getStatus());

        this.updateById(update);

        LambdaUpdateWrapper<StudioQuizQuestion> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.eq(StudioQuizQuestion::getId, request.getId());
        boolean needClear = false;
        if (request.getAnalysis() != null && normalized.analysis() == null) {
            clearWrapper.set(StudioQuizQuestion::getAnalysis, null);
            needClear = true;
        }
        if (request.getSource() != null && update.getSource() == null) {
            clearWrapper.set(StudioQuizQuestion::getSource, null);
            needClear = true;
        }
        if (needClear) {
            this.baseMapper.update(null, clearWrapper);
        }

        if (verdictChanged) {
            resetWrongStreak(existing.getId());
        }

        // 留痕：只写「改了哪些字段」。选项与解析全文不进日志——它既非排查必需，也扩大隐私面
        operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_UPDATE, request.getId(),
                stemSummary(normalized.stem()), describeQuestionChanges(request, verdictChanged));
        return true;
    }

    /** 删除一道题目：逻辑删除题目，并在同一事务里清掉它对应的错题条目 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteQuestion(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // 先确认存在：不存在时给 A0402，前端才能区分「删掉了」与「本来就没有」
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        removeWrongEntries(List.of(id));

        // 作答记录保留：统计只读 result，不需要题目内容
        boolean removed = this.removeById(id);
        if (removed) {
            // 题干必须在删除前取：log 里再查一次既多一次 IO，题目也已经查不到了
            operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_DELETE, id, stemSummary(existing.getStem()),
                    "教材：" + existing.getTextbookName() + "；章节：" + existing.getChapterName()
                            + "；同事务清理错题条目，作答记录保留");
        }
        return removed ? 1 : 0;
    }

    /** 批量删除题目：不存在的条目跳过，返回实际删除条数 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteQuestions(List<Long> ids) {
        ThrowUtils.throwIf(ids == null || ids.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择要删除的题目");

        // 去重后再删：前端勾选时可能出现重复 id（分页切换后选中状态串了）
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        int deleted = 0;
        List<Long> deletedIds = new ArrayList<>();
        for (Long id : distinctIds) {
            // 列表来自前端，不能假定每个元素都合法：null 与非正数直接跳过
            if (id == null || id <= 0) {
                continue;
            }
            if (this.getById(id) == null) {
                continue;
            }
            removeWrongEntries(List.of(id));
            if (this.removeById(id)) {
                deleted++;
                deletedIds.add(id);
            }
        }

        // 留痕：整批一行。逐题一行会让「批量删两百道」把日志表刷满，而排查时看的是同一批
        if (deleted > 0) {
            operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_BATCH_DELETE, null, null,
                    "批量删除 " + deleted + " 道题；ID：" + CollUtil.join(deletedIds, ","));
        }
        return deleted;
    }

    /** 启用 / 停用一道题：只改 status 一列，其它数据一概不动 */
    @Override
    public boolean updateStatus(long id, int status) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // 状态是闭集：非法值必须报错，静默当成启用会让管理员以为下线成功了
        ThrowUtils.throwIf(status != STATUS_ENABLED && status != STATUS_DISABLED,
                ErrorCode.PARAMS_ERROR, "状态只能是 0（启用）或 1（停用）");
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // 停用只改 status：不动错题条目，因此重新启用后条目自动回来（可逆）
        StudioQuizQuestion update = new StudioQuizQuestion();
        update.setId(id);
        update.setStatus(status);
        boolean updated = this.updateById(update);

        // 留痕：一次停用就是把一道题从所有人的刷题范围里拿掉，属于「内容下线」
        if (updated) {
            operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_STATUS, id, stemSummary(existing.getStem()),
                    status == STATUS_ENABLED ? "启用" : "停用");
        }
        return updated;
    }

    /** 批量导入题目：先把整段文本解析成题目，全部合法才逐条入库 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizImportResultVO importQuestions(QuizQuestionImportRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // 解析器只做语法校验，不碰数据库
        QuizImportParser.ParseResult parsed = QuizImportParser.parse(request.getText());
        QuizImportResultVO result = new QuizImportResultVO();
        result.setDryRun(Boolean.TRUE.equals(request.getDryRun()));
        result.setParsed(parsed.questions().size());
        result.setImported(0);
        result.setErrors(new ArrayList<>());

        // 解析阶段的错误先收齐（行号对应粘贴文本的物理行）
        for (QuizImportParser.LineError error : parsed.errors()) {
            result.getErrors().add(new QuizImportResultVO.ImportError(error.line(), error.message()));
        }

        // 整批级别的错误行号记 0：它不是某一行的问题，前端要单独渲染
        if (parsed.questions().size() > QuizConstant.IMPORT_MAX_QUESTIONS) {
            result.getErrors().add(new QuizImportResultVO.ImportError(0,
                    "单次最多导入 " + QuizConstant.IMPORT_MAX_QUESTIONS + " 道题，本次 " + parsed.questions().size() + " 道"));
        }

        // 逐条走与新增相同的校验；单条失败只记录不抛出，才能把问题一次报全
        List<NormalizedQuestion> normalizedList = new ArrayList<>();
        int startSeq = defaultIfNull(request.getStartSeq(), DEFAULT_SEQ);
        for (int i = 0; i < parsed.questions().size(); i++) {
            QuizImportParser.ParsedQuestion question = parsed.questions().get(i);
            try {
                NormalizedQuestion normalized = validateAndNormalize(
                        question.type().getValue(), question.stem(),
                        toOptionVOs(question.options()), question.correctLabels(), question.analysis());
                normalizedList.add(normalized);
            } catch (BusinessException e) {
                result.getErrors().add(new QuizImportResultVO.ImportError(
                        question.startLine(), e.getMessage()));
            }
        }

        // 有任何错误：整批不落库（「导入了一半」会让管理员无法判断哪些进了）
        if (!result.getErrors().isEmpty()) {
            return result;
        }
        // 预览模式：校验全过也不落库，imported 保持 0
        if (Boolean.TRUE.equals(request.getDryRun())) {
            return result;
        }

        // 全部合法才逐条入库；normalizedList 与 parsed.questions() 按下标一一对应
        for (int i = 0; i < parsed.questions().size(); i++) {
            QuizImportParser.ParsedQuestion parsedQuestion = parsed.questions().get(i);
            NormalizedQuestion normalized = normalizedList.get(i);

            StudioQuizQuestion question = new StudioQuizQuestion();
            question.setTextbookName(request.getTextbookName().trim());
            question.setTextbookSeq(defaultIfNull(request.getTextbookSeq(), DEFAULT_SEQ));
            question.setChapterName(request.getChapterName().trim());
            question.setChapterSeq(defaultIfNull(request.getChapterSeq(), DEFAULT_SEQ));
            question.setType(normalized.type().getValue());
            question.setStem(normalized.stem());
            question.setOptions(quizOptionCodec.encode(normalized.options()));
            question.setCorrectLabels(normalized.correctLabels());
            question.setAnalysis(normalized.analysis());
            // 导入不提供难度字段：统一按默认难度入库，避免整批都是同一个随意值却看不出来
            question.setDifficulty(DEFAULT_DIFFICULTY);
            // 题序从 startSeq 起依次 +1：便于「第一章 0-49、第二章 50-99」这样分段导入
            question.setQuestionSeq(startSeq + i);
            question.setStatus(STATUS_ENABLED);
            ThrowUtils.throwIf(!this.save(question), ErrorCode.OPERATION_ERROR, "导入失败，数据库异常");
            result.setImported(result.getImported() + 1);
        }
        log.info("题目批量导入完成 | 教材={} | 章节={} | 数量={}",
                request.getTextbookName(), request.getChapterName(), result.getImported());

        operationLogService.record(OperationTypeEnum.QUIZ_QUESTION_IMPORT, null,
                request.getTextbookName().trim() + " / " + request.getChapterName().trim(),
                "导入 " + result.getImported() + " 道题；题序从 " + startSeq + " 起");
        return result;
    }

    // 管理端：章节 / 教材批量操作

    /** 管理端章节列表：一条 GROUP BY 聚合出「每本教材的每一章有多少题、其中启用几题」 */
    @Override
    public List<QuizChapterAdminVO> listChaptersForAdmin() {
        // 一条 GROUP BY 同时算总题数与启用题数：不逐章 count，避免 N+1
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        // 管理端口径：含停用题（管理员要看存量），另外单独给启用数
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq",
                        "COUNT(*) AS cnt",
                        "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS enabled_cnt")
                .groupBy("textbook_name", "chapter_name");

        List<QuizChapterAdminVO> result = new ArrayList<>();
        // 逐行转 VO；教材名或章节名为 null 的脏数据行直接跳过
        for (Map<String, Object> row : this.listMaps(wrapper)) {
            String textbookName = asString(row.get("textbook_name"));
            String chapterName = asString(row.get("chapter_name"));
            if (textbookName == null || chapterName == null) {
                continue;
            }
            QuizChapterAdminVO vo = new QuizChapterAdminVO();
            vo.setTextbookName(textbookName);
            vo.setTextbookSeq(asInt(row.get("textbook_seq")));
            vo.setName(chapterName);
            vo.setSeq(asInt(row.get("chapter_seq")));
            vo.setQuestionCount(asInt(row.get("cnt")));
            vo.setEnabledCount(asInt(row.get("enabled_cnt")));
            result.add(vo);
        }
        // 四级排序保证两次请求顺序一致（只按顺序值排时，相同值之间顺序不确定）
        result.sort(Comparator.comparing(QuizChapterAdminVO::getTextbookSeq)
                .thenComparing(QuizChapterAdminVO::getTextbookName)
                .thenComparing(QuizChapterAdminVO::getSeq)
                .thenComparing(QuizChapterAdminVO::getName));
        return result;
    }

    /** 章节改名（整章批量改名，可顺带调序） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameChapter(QuizChapterRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 必须在查询之前：否则「 第一章」与「第一章」会被当成两个章节
        String textbookName = request.getTextbookName().trim();
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        // 先确认章节存在：不存在时给 A0402，而不是让 UPDATE 影响 0 行后被误认为成功
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        if (!oldName.equals(newName)) {
            long targetExists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                    .eq(StudioQuizQuestion::getTextbookName, textbookName)
                    .eq(StudioQuizQuestion::getChapterName, newName));
            if (targetExists > 0) {
                log.info("章节改名合并 | 教材={} | {} -> {}（目标已存在 {} 题）",
                        textbookName, oldName, newName, targetExists);
            }
        }

        // 一条 UPDATE 改整章；chapterSeq 传了才改，没传保持原值（「只改名不动顺序」更常见）
        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName)
                .set(StudioQuizQuestion::getChapterName, newName)
                .set(StudioQuizQuestion::getUpdatedBy, StpUtil.getLoginIdAsLong())
                .set(StudioQuizQuestion::getUpdatedAt, LocalDateTime.now());
        if (request.getChapterSeq() != null) {
            update.set(StudioQuizQuestion::getChapterSeq, request.getChapterSeq());
        }
        int affected = this.baseMapper.update(null, update);

        // 留痕：章节没有独立 ID，用「教材 / 旧名 → 新名」定位；受影响题数是最有用的规模信息
        if (affected > 0) {
            operationLogService.record(OperationTypeEnum.QUIZ_CHAPTER_RENAME, null,
                    textbookName + " / " + oldName + " → " + newName,
                    "影响题目 " + affected + " 道"
                            + (request.getChapterSeq() == null ? "" : "；章节顺序值改为 " + request.getChapterSeq()));
        }
        return affected;
    }

    /** 移动章节：把整章挪到另一本教材下，可顺带改教材顺序值 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveChapter(QuizChapterMoveRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 必须在查询之前，否则前后空格会导致「章节不存在」的误判
        String fromTextbook = request.getFromTextbook().trim();
        String chapterName = request.getChapterName().trim();
        String toTextbook = request.getToTextbook().trim();

        // 校验来源章节存在，不存在时给 A0402 而不是让 UPDATE 影响 0 行
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, fromTextbook)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        // 只改教材名：目标教材不必已存在（教材只是名字列，移过去就等于新建）
        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, fromTextbook)
                .eq(StudioQuizQuestion::getChapterName, chapterName)
                .set(StudioQuizQuestion::getTextbookName, toTextbook)
                // 同 renameChapter：Wrapper 批量更新必须显式写审计列
                .set(StudioQuizQuestion::getUpdatedBy, StpUtil.getLoginIdAsLong())
                .set(StudioQuizQuestion::getUpdatedAt, LocalDateTime.now());
        if (request.getToTextbookSeq() != null) {
            update.set(StudioQuizQuestion::getTextbookSeq, request.getToTextbookSeq());
        }
        int affected = this.baseMapper.update(null, update);

        // 留痕：整章换教材会改变题库结构（原教材可能因此空掉）
        if (affected > 0) {
            operationLogService.record(OperationTypeEnum.QUIZ_CHAPTER_MOVE, null,
                    chapterName + "：" + fromTextbook + " → " + toTextbook,
                    "影响题目 " + affected + " 道"
                            + (request.getToTextbookSeq() == null ? "" : "；教材顺序值改为 " + request.getToTextbookSeq()));
        }
        return affected;
    }

    /** 删除整章：逻辑删除该章的全部题目，并在同一事务里清掉它们的错题条目 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteChapter(QuizChapterDeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String textbookName = request.getTextbookName().trim();
        String chapterName = request.getChapterName().trim();

        // 只 select 主键：后面用不到题干与选项这些大字段
        List<StudioQuizQuestion> questions = this.list(new LambdaQueryWrapper<StudioQuizQuestion>()
                .select(StudioQuizQuestion::getId)
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(questions.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        List<Long> ids = questions.stream().map(StudioQuizQuestion::getId).toList();
        // 一次性清掉这批题目的错题条目：按 ID 列表批量删，不是逐条
        removeWrongEntries(ids);

        // 逐条逻辑删除并计数：需要准确的「实际删掉几条」，因此不用一条批量 UPDATE
        int deleted = 0;
        for (Long id : ids) {
            if (this.removeById(id)) {
                deleted++;
            }
        }
        log.info("整章删除 | 教材={} | 章节={} | 删除 {} 题", textbookName, chapterName, deleted);

        // 留痕：删整章不可逆（作答记录保留但题目已不在），是题库里破坏性最大的操作
        operationLogService.record(OperationTypeEnum.QUIZ_CHAPTER_DELETE, null,
                textbookName + " / " + chapterName,
                "删除整章，共 " + deleted + " 道题；同事务清理错题条目，作答记录保留");
        return deleted;
    }

    /** 重命名教材：把整本教材的所有题目改到新名字下，可顺带改教材顺序值 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameTextbook(QuizTextbookRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // trim 必须在查询之前，否则前后空格会直接走到「教材不存在」
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        // 先确认教材存在：否则影响 0 行时分不清「本来就没题」与「名字打错了」
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "教材不存在");

        // 一条 UPDATE 改整本教材；textbookSeq 传了才改，避免打乱已排好的顺序
        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, oldName)
                .set(StudioQuizQuestion::getTextbookName, newName)
                .set(StudioQuizQuestion::getUpdatedBy, StpUtil.getLoginIdAsLong())
                .set(StudioQuizQuestion::getUpdatedAt, LocalDateTime.now());
        if (request.getTextbookSeq() != null) {
            update.set(StudioQuizQuestion::getTextbookSeq, request.getTextbookSeq());
        }
        int affected = this.baseMapper.update(null, update);

        // 留痕：全库级批量改名，影响面可能覆盖整本教材（含其所有章节）
        if (affected > 0) {
            operationLogService.record(OperationTypeEnum.QUIZ_TEXTBOOK_RENAME, null,
                    oldName + " → " + newName,
                    "影响题目 " + affected + " 道"
                            + (request.getTextbookSeq() == null ? "" : "；教材顺序值改为 " + request.getTextbookSeq()));
        }
        return affected;
    }

    // 私有：校验与转换

    /** 题干预览（写进操作日志的 target_title） */
    private String stemSummary(String stem) {
        if (StrUtil.isBlank(stem)) {
            return "（空题干）";
        }
        String flat = stem.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    /** 把一次题目修改翻译成「改了哪些字段」（写进操作日志的 detail） */
    private String describeQuestionChanges(QuizQuestionUpdateRequest request, boolean verdictChanged) {
        StringBuilder fields = new StringBuilder();
        appendField(fields, request.getStem() != null, "题干");
        appendField(fields, request.getType() != null, "题型");
        appendField(fields, request.getOptions() != null, "选项");
        appendField(fields, request.getCorrectLabels() != null, "正确答案");
        appendField(fields, request.getAnalysis() != null, "解析");
        appendField(fields, request.getDifficulty() != null, "难度");
        appendField(fields, request.getSource() != null, "题源");
        appendField(fields, request.getTextbookName() != null || request.getTextbookSeq() != null, "教材");
        appendField(fields, request.getChapterName() != null || request.getChapterSeq() != null, "章节");
        appendField(fields, request.getQuestionSeq() != null, "题序");
        appendField(fields, request.getStatus() != null, "状态");

        String detail = fields.length() == 0 ? "提交了修改请求，字段无变化" : "修改字段：" + fields;
        return verdictChanged ? detail + "；判定相关变更，已重置错题连对进度" : detail;
    }

    /** 追加一个字段名（顿号分隔） */
    private void appendField(StringBuilder fields, boolean changed, String label) {
        if (!changed) {
            return;
        }
        if (fields.length() > 0) {
            fields.append("、");
        }
        fields.append(label);
    }

    /** 跨字段校验 + 规范化（新增 / 修改 / 导入三条入口的唯一校验点） */
    private NormalizedQuestion validateAndNormalize(int type, String stem, List<QuizOptionVO> options,
                                                    String correctLabels, String analysis) {
        // 题型：闭集，非法值必须报错而不是默认成单选
        QuizQuestionTypeEnum questionType = QuizQuestionTypeEnum.of(type);
        ThrowUtils.throwIf(questionType == null, ErrorCode.PARAMS_ERROR,
                "题型取值非法，合法值为 " + QuizQuestionTypeEnum.valuesText());

        // 选项结构：数量在范围内、标签从 A 连续、正文非空
        ThrowUtils.throwIf(options == null || options.isEmpty(), ErrorCode.PARAMS_ERROR, "选项不能为空");
        int minOptions = questionType == QuizQuestionTypeEnum.SINGLE
                ? QuizConstant.OPTION_MIN_SINGLE : QuizConstant.OPTION_MIN_MULTIPLE;
        ThrowUtils.throwIf(options.size() < minOptions, ErrorCode.PARAMS_ERROR,
                questionType.getDesc() + "题至少需要 " + minOptions + " 个选项");
        ThrowUtils.throwIf(options.size() > QuizConstant.OPTION_MAX, ErrorCode.PARAMS_ERROR,
                "选项不能超过 " + QuizConstant.OPTION_MAX + " 个");

        List<String> labels = new ArrayList<>();
        for (QuizOptionVO option : options) {
            ThrowUtils.throwIf(option == null, ErrorCode.PARAMS_ERROR, "选项不能为空");
            String label = StrUtil.trimToEmpty(option.getLabel()).toUpperCase();
            ThrowUtils.throwIf(label.length() != 1, ErrorCode.PARAMS_ERROR, "选项标签只能是单个字母");
            ThrowUtils.throwIf(StrUtil.isBlank(option.getContent()), ErrorCode.PARAMS_ERROR,
                    "选项 " + label + " 的内容不能为空");
            ThrowUtils.throwIf(option.getContent().length() > QuizConstant.OPTION_CONTENT_MAX_LENGTH,
                    ErrorCode.PARAMS_ERROR, "选项 " + label + " 的内容不能超过 "
                            + QuizConstant.OPTION_CONTENT_MAX_LENGTH + " 个字符");
            labels.add(label);
            option.setLabel(label);
            option.setContent(option.getContent().trim());
        }
        ThrowUtils.throwIf(!labels.equals(QuizLabelUtils.sequentialLabels(labels.size())),
                ErrorCode.PARAMS_ERROR,
                "选项标签必须从 A 开始连续，实际为 " + String.join("、", labels));

        // 正确项：先查非法字符与重复，再按题型校验个数
        Character invalid = QuizLabelUtils.findInvalidLabel(correctLabels);
        ThrowUtils.throwIf(invalid != null, ErrorCode.PARAMS_ERROR,
                "正确答案包含非法字符或重复标签：" + invalid);
        String normalizedCorrect = QuizLabelUtils.normalize(correctLabels);
        ThrowUtils.throwIf(normalizedCorrect.isEmpty(), ErrorCode.PARAMS_ERROR, "正确答案不能为空");
        ThrowUtils.throwIf(!labels.containsAll(QuizLabelUtils.toLabelList(normalizedCorrect)),
                ErrorCode.PARAMS_ERROR, "正确答案必须是已有选项");

        if (questionType == QuizQuestionTypeEnum.SINGLE) {
            ThrowUtils.throwIf(normalizedCorrect.length() != 1, ErrorCode.PARAMS_ERROR,
                    "单选题只能有 1 个正确项");
        } else {
            ThrowUtils.throwIf(normalizedCorrect.length() < 2, ErrorCode.PARAMS_ERROR,
                    "多选题至少要有 2 个正确项");
        }

        // 题干与解析
        String trimmedStem = StrUtil.trimToEmpty(stem);
        ThrowUtils.throwIf(trimmedStem.isEmpty(), ErrorCode.PARAMS_ERROR, "题干不能为空");
        ThrowUtils.throwIf(trimmedStem.length() > QuizConstant.STEM_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "题干不能超过 " + QuizConstant.STEM_MAX_LENGTH + " 个字符");

        // 解析允许为空：空串统一转成 null（全局约定「空值存 NULL」）
        String trimmedAnalysis = StrUtil.trimToNull(analysis);
        ThrowUtils.throwIf(trimmedAnalysis != null && trimmedAnalysis.length() > QuizConstant.ANALYSIS_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "解析不能超过 " + QuizConstant.ANALYSIS_MAX_LENGTH + " 个字符");

        return new NormalizedQuestion(questionType, trimmedStem, options, normalizedCorrect, trimmedAnalysis);
    }

    /** 判定相关变更检测 */
    private boolean isVerdictChanged(StudioQuizQuestion existing, NormalizedQuestion normalized) {
        if (!Objects.equals(existing.getCorrectLabels(), normalized.correctLabels())) {
            return true;
        }
        if (!Objects.equals(existing.getType(), normalized.type().getValue())) {
            return true;
        }
        // 只比 label 集合，不比 content：改选项文字不影响判定的可解释性
        List<String> oldLabels = quizOptionCodec.labelsOf(quizOptionCodec.decode(existing.getOptions()));
        List<String> newLabels = quizOptionCodec.labelsOf(normalized.options());
        return !Objects.equals(oldLabels, newLabels);
    }

    /** 重置某题所有用户错题条目的连对进度 */
    private void resetWrongStreak(long questionId) {
        LambdaUpdateWrapper<StudioQuizWrong> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizWrong::getQuestionId, questionId)
                .set(StudioQuizWrong::getCorrectStreak, 0);
        int affected = studioQuizWrongMapper.update(null, update);
        log.info("题目判定相关变更，已重置错题连对进度 | questionId={} | 影响 {} 条", questionId, affected);
    }

    /** 清理指定题目的全部错题条目（逻辑删除） */
    private void removeWrongEntries(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioQuizWrong::getQuestionId, questionIds);
        studioQuizWrongMapper.delete(wrapper);
    }

    /** 批量填充作答数与正确率 */
    private void fillAnswerStats(List<QuizQuestionAdminVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> questionIds = records.stream().map(QuizQuestionAdminVO::getId).toList();
        QueryWrapper<StudioQuizAnswer> wrapper = new QueryWrapper<>();
        wrapper.select("question_id", "COUNT(*) AS cnt",
                        "SUM(CASE WHEN result = 1 THEN 1 ELSE 0 END) AS correct_cnt")
                .in("question_id", questionIds)
                .in("result", QuizAnswerResultEnum.CORRECT.getValue(), QuizAnswerResultEnum.WRONG.getValue())
                .groupBy("question_id");

        Map<Long, int[]> stats = new HashMap<>();
        for (Map<String, Object> row : studioQuizAnswerMapper.selectMaps(wrapper)) {
            Long questionId = asLong(row.get("question_id"));
            if (questionId == null) {
                continue;
            }
            stats.put(questionId, new int[]{asInt(row.get("cnt")), asInt(row.get("correct_cnt"))});
        }

        for (QuizQuestionAdminVO vo : records) {
            int[] stat = stats.get(vo.getId());
            int total = stat == null ? 0 : stat[0];
            int correct = stat == null ? 0 : stat[1];
            vo.setAnswerCount(total);
            // 分母为 0 时给 null：0% 读起来像「全错」，那是错误的信息
            vo.setAccuracy(total == 0 ? null : (double) correct / total);
        }
    }

    /** 实体 → 管理端 VO（含选项与答案） */
    private QuizQuestionAdminVO toAdminVO(StudioQuizQuestion question) {
        if (question == null) {
            return null;
        }
        QuizQuestionAdminVO vo = new QuizQuestionAdminVO();
        vo.setId(question.getId());
        vo.setTextbookName(question.getTextbookName());
        vo.setTextbookSeq(question.getTextbookSeq());
        vo.setChapterName(question.getChapterName());
        vo.setChapterSeq(question.getChapterSeq());
        vo.setType(question.getType());
        vo.setStem(question.getStem());
        vo.setOptions(quizOptionCodec.decode(question.getOptions()));
        vo.setCorrectLabels(question.getCorrectLabels());
        vo.setAnalysis(question.getAnalysis());
        vo.setDifficulty(question.getDifficulty());
        vo.setSource(question.getSource());
        vo.setQuestionSeq(question.getQuestionSeq());
        vo.setStatus(question.getStatus());
        vo.setCreatedAt(question.getCreatedAt());
        vo.setUpdatedAt(question.getUpdatedAt());
        return vo;
    }

    /** DTO 的选项 → VO（字段一致，但两者刻意分开：入参未来可能加临时字段） */
    private List<QuizOptionVO> toOptionVOs(List<QuizOptionItem> items) {
        List<QuizOptionVO> options = new ArrayList<>();
        if (items == null) {
            return options;
        }
        for (QuizOptionItem item : items) {
            if (item == null) {
                continue;
            }
            QuizOptionVO vo = new QuizOptionVO();
            vo.setLabel(item.getLabel());
            vo.setContent(item.getContent());
            options.add(vo);
        }
        return options;
    }

    /** 分页参数兜底纠正 */
    private Page<StudioQuizQuestion> newPageWithDefaults(Long current, Long pageSize) {
        long page = current == null || current < 1 ? 1L : current;
        long size = pageSize == null || pageSize < 1 ? QuizConstant.DEFAULT_PAGE_SIZE : pageSize;
        return new Page<>(page, Math.min(size, QuizConstant.MAX_PAGE_SIZE));
    }

    /** 取 int 值，null 用默认值 */
    private static int defaultIfNull(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** 聚合结果的数值转 int（走 Number：不同驱动下 COUNT 可能是 BigInteger） */
    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 聚合结果的数值转 Long */
    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 聚合结果的字符串转 String（null 保持 null） */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 校验并规范化后的题目数据（内部传递用） */
    private record NormalizedQuestion(QuizQuestionTypeEnum type, String stem, List<QuizOptionVO> options,
                                      String correctLabels, String analysis) {
    }
}

package com.bhu.runshistudioweb.service.impl;

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
import com.bhu.runshistudioweb.model.enums.QuizAnswerResultEnum;
import com.bhu.runshistudioweb.model.enums.QuizQuestionTypeEnum;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizImportResultVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizOptionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizTextbookVO;
import com.bhu.runshistudioweb.service.QuizQuestionService;
import com.bhu.runshistudioweb.utils.QuizLabelUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 本类最重要的三条规则（改代码前先读）：
 * - 跨字段校验只有一处：validateAndNormalize。
 * 新增 / 修改 / 导入三条入口都调它，绝不允许某一处「顺手写个 if」——
 * 三条入口的规则一旦分叉，「导入能进、编辑不让改」这类问题就会出现，
 * 而且不会报错，只会让管理员困惑；
 * - 修改是「用旧值补齐后整体校验」：否则「只改解析」会因为
 * 没传选项而报「选项不能为空」——这是最常见的误伤；
 * - 判定相关变更要重置错题连对进度：改答案 / 改题型 / 增删选项之后，
 * 用户之前「连对 1 次」的证据不再成立。但不动 status——
 * 已消解的题不该因为管理员改了个字就退回待复习。
 *
 * 为什么注入两个 Mapper 而不是 Service：
 * 删除题目要清理错题条目、列表要统计作答数，这些是「按条件批量写/聚合读」，
 * 走 Mapper 最直接。反向注入 QuizWrongService 会形成循环依赖
 * （错题服务要读题目表），项目已全量改为构造器注入，成环会启动即失败。
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

    // ==================== C 端：教材 / 章节树 ====================

    /**
     * 教材 → 章节树（C 端下拉）
     *
     * 一次 GROUP BY 查完再内存挂树，不是「先查教材、再逐个查章节」——
     * 后者在教材有十几本时就是十几次查询，而这里只需要一次。
     *
     * 只统计 status=0 的题：用户看到的数字是「这一章有多少题可刷」。
     */
    @Override
    public List<QuizTextbookVO> listCatalog() {
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        // 用字符串列名 + 聚合：Lambda 版表达不了 MIN/MAX/COUNT。
        // 列名全是硬编码常量，不存在拼接用户输入的注入面
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq", "COUNT(*) AS cnt")
                .eq("status", STATUS_ENABLED)
                .groupBy("textbook_name", "chapter_name");

        List<Map<String, Object>> rows = this.listMaps(wrapper);

        // 用 LinkedHashMap 保留插入顺序，最后再按教材顺序整体排序：
        // 这样「先按教材、再按章节」的排序只需在内存里做一次
        Map<String, QuizTextbookVO> textbookMap = new LinkedHashMap<>();
        Map<String, QuizChapterVO> chapterMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String textbookName = asString(row.get("textbook_name"));
            String chapterName = asString(row.get("chapter_name"));
            if (textbookName == null || chapterName == null) {
                continue;
            }
            int textbookSeq = asInt(row.get("textbook_seq"));
            int chapterSeq = asInt(row.get("chapter_seq"));
            int count = asInt(row.get("cnt"));

            QuizTextbookVO textbook = textbookMap.computeIfAbsent(textbookName, name -> {
                QuizTextbookVO vo = new QuizTextbookVO();
                vo.setName(name);
                vo.setSeq(textbookSeq);
                vo.setQuestionCount(0);
                vo.setChapters(new ArrayList<>());
                return vo;
            });
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

        List<QuizTextbookVO> result = new ArrayList<>(textbookMap.values());
        result.sort(Comparator.comparing(QuizTextbookVO::getSeq, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(QuizTextbookVO::getName));
        for (QuizTextbookVO textbook : result) {
            textbook.getChapters().sort(
                    Comparator.comparing(QuizChapterVO::getSeq, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(QuizChapterVO::getName));
        }
        return result;
    }

    // ==================== 管理端：题目读写 ====================

    @Override
    public Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request) {
        QuizQuestionQueryRequest query = request == null ? new QuizQuestionQueryRequest() : request;

        // 题型是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
        if (query.getType() != null && QuizQuestionTypeEnum.of(query.getType()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "题型取值非法，合法值为 " + QuizQuestionTypeEnum.valuesText());
        }
        if (query.getStatus() != null && query.getStatus() != STATUS_ENABLED
                && query.getStatus() != STATUS_DISABLED) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "状态只能是 0（启用）或 1（停用）");
        }

        LambdaQueryWrapper<StudioQuizQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(query.getTextbookName() != null, StudioQuizQuestion::getTextbookName, query.getTextbookName())
                .eq(query.getChapterName() != null, StudioQuizQuestion::getChapterName, query.getChapterName())
                .eq(query.getType() != null, StudioQuizQuestion::getType, query.getType())
                .eq(query.getStatus() != null, StudioQuizQuestion::getStatus, query.getStatus())
                .like(StrUtil.isNotBlank(query.getKeyword()), StudioQuizQuestion::getStem, query.getKeyword())
                // 教材 → 章节 → 题序：与 idx_scope 同向，也让「同一章的题连在一起」
                .orderByAsc(StudioQuizQuestion::getTextbookSeq)
                .orderByAsc(StudioQuizQuestion::getTextbookName)
                .orderByAsc(StudioQuizQuestion::getChapterSeq)
                .orderByAsc(StudioQuizQuestion::getChapterName)
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                .orderByAsc(StudioQuizQuestion::getId);

        Page<StudioQuizQuestion> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);

        List<QuizQuestionAdminVO> records = entityPage.getRecords().stream()
                .map(this::toAdminVO)
                .collect(Collectors.toList());
        fillAnswerStats(records);

        Page<QuizQuestionAdminVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public QuizQuestionAdminVO getQuestionById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        StudioQuizQuestion question = this.getById(id);
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        QuizQuestionAdminVO vo = toAdminVO(question);
        fillAnswerStats(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addQuestion(QuizQuestionAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // 先把 DTO 的选项转成 VO（编解码统一走 QuizOptionCodec，避免两处格式定义）
        List<QuizOptionVO> options = toOptionVOs(request.getOptions());
        NormalizedQuestion normalized = validateAndNormalize(
                request.getType(), request.getStem(), options, request.getCorrectLabels(),
                request.getAnalysis());

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
        question.setStatus(STATUS_ENABLED);

        boolean saved = this.save(question);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "新增题目失败，数据库异常");
        return question.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateQuestion(QuizQuestionUpdateRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null,
                ErrorCode.PARAMS_ERROR, "题目 id 不能为空");

        StudioQuizQuestion existing = this.getById(request.getId());
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // ① 用旧值补齐：只传了 analysis 时，其余字段沿用旧值。
        //    这是「部分更新」能通过整体校验的前提
        int newType = request.getType() == null ? existing.getType() : request.getType();
        String newStem = request.getStem() == null ? existing.getStem() : request.getStem();
        List<QuizOptionVO> newOptions = request.getOptions() == null
                ? quizOptionCodec.decode(existing.getOptions())
                : toOptionVOs(request.getOptions());
        String newCorrect = request.getCorrectLabels() == null
                ? existing.getCorrectLabels() : request.getCorrectLabels();
        // 解析同样要「用旧值补齐」：只改题干时不该把解析清空
        String newAnalysis = request.getAnalysis() == null ? existing.getAnalysis() : request.getAnalysis();

        // ② 整体校验（与新增共用同一套规则）
        NormalizedQuestion normalized = validateAndNormalize(newType, newStem, newOptions, newCorrect, newAnalysis);

        // ③ 判定相关变更检测：必须在覆盖旧值之前算，否则永远相等
        boolean verdictChanged = isVerdictChanged(existing, normalized);

        // ④ 只 set 允许修改的字段：服务端写死的白名单，
        //    绝不能把 DTO 直接转实体 updateById（那样前端能改 deletedAt、审计字段）
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

        // 注意：这里不判断 updateById 的返回值。
        // MySQL 默认协议下「SET 的值与原来完全相同」会返回 0 行受影响，
        // 而「管理员打开编辑框、什么都没改、点保存」是极常见的操作——
        // 把它当成失败会让用户看到一条莫名其妙的「更新失败」。
        // 存在性已在上面用 getById 校验过，因此 0 行受影响是安全的 no-op。
        this.updateById(update);

        // ⑤ 清空解析 / 题源：updateById 会跳过 null 字段，所以「改成空」必须显式 set。
        //    这也是为什么不能只靠实体更新——否则用户永远清不掉写错的解析
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

        // ⑥ 判定相关变更：重置该题所有用户错题条目的连对进度。
        //    status 保持不变——已消解的不退回待复习（用户曾经掌握过，
        //    管理员改题不该否定这一点）；只把「连对几次」的证据作废
        if (verdictChanged) {
            resetWrongStreak(existing.getId());
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteQuestion(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // 同一事务内先清错题条目，再逻辑删除题目：
        // 反过来的话，题目删除失败会留下「条目指向不存在的题」的脏数据
        removeWrongEntries(List.of(id));

        // 作答记录保留：统计只读 result，不需要题目内容
        return this.removeById(id) ? 1 : 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteQuestions(List<Long> ids) {
        ThrowUtils.throwIf(ids == null || ids.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择要删除的题目");

        // 去重后再删：前端勾选时可能出现重复 id（分页切换后选中状态串了）
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        int deleted = 0;
        for (Long id : distinctIds) {
            if (id == null || id <= 0) {
                continue;
            }
            // 某条不存在时不中断：批量删除的语义是「把这些都删掉」，
            // 其中一条已被别人删掉不该让整批失败
            if (this.getById(id) == null) {
                continue;
            }
            removeWrongEntries(List.of(id));
            if (this.removeById(id)) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public boolean updateStatus(long id, int status) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        ThrowUtils.throwIf(status != STATUS_ENABLED && status != STATUS_DISABLED,
                ErrorCode.PARAMS_ERROR, "状态只能是 0（启用）或 1（停用）");
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // 停用只改 status：不动错题条目，因此重新启用后条目自动回来（可逆）
        StudioQuizQuestion update = new StudioQuizQuestion();
        update.setId(id);
        update.setStatus(status);
        return this.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizImportResultVO importQuestions(QuizQuestionImportRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        QuizImportParser.ParseResult parsed = QuizImportParser.parse(request.getText());
        QuizImportResultVO result = new QuizImportResultVO();
        result.setDryRun(Boolean.TRUE.equals(request.getDryRun()));
        result.setParsed(parsed.questions().size());
        result.setImported(0);
        result.setErrors(new ArrayList<>());

        // 解析阶段的错误先收齐
        for (QuizImportParser.LineError error : parsed.errors()) {
            result.getErrors().add(new QuizImportResultVO.ImportError(error.line(), error.message()));
        }

        if (parsed.questions().size() > QuizConstant.IMPORT_MAX_QUESTIONS) {
            result.getErrors().add(new QuizImportResultVO.ImportError(0,
                    "单次最多导入 " + QuizConstant.IMPORT_MAX_QUESTIONS + " 道题，本次 " + parsed.questions().size() + " 道"));
        }

        // 逐条走与新增相同的校验；任一条失败则整批不落库
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
        if (Boolean.TRUE.equals(request.getDryRun())) {
            return result;
        }

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
            question.setDifficulty(DEFAULT_DIFFICULTY);
            question.setQuestionSeq(startSeq + i);
            question.setStatus(STATUS_ENABLED);
            ThrowUtils.throwIf(!this.save(question), ErrorCode.OPERATION_ERROR, "导入失败，数据库异常");
            result.setImported(result.getImported() + 1);
        }
        log.info("题目批量导入完成 | 教材={} | 章节={} | 数量={}",
                request.getTextbookName(), request.getChapterName(), result.getImported());
        return result;
    }

    // ==================== 管理端：章节 / 教材批量操作 ====================

    @Override
    public List<QuizChapterAdminVO> listChaptersForAdmin() {
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        // 管理端口径：含停用题（管理员要看存量），另外单独给启用数
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq",
                        "COUNT(*) AS cnt",
                        "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS enabled_cnt")
                .groupBy("textbook_name", "chapter_name");

        List<QuizChapterAdminVO> result = new ArrayList<>();
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
        result.sort(Comparator.comparing(QuizChapterAdminVO::getTextbookSeq)
                .thenComparing(QuizChapterAdminVO::getTextbookName)
                .thenComparing(QuizChapterAdminVO::getSeq)
                .thenComparing(QuizChapterAdminVO::getName));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameChapter(QuizChapterRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String textbookName = request.getTextbookName().trim();
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        // 改名到已存在的章节时合并：不报错。用户想的大概率就是合并，
        // 报错会让他不得不先把两章改成不同名字再改回来
        if (!oldName.equals(newName)) {
            long targetExists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                    .eq(StudioQuizQuestion::getTextbookName, textbookName)
                    .eq(StudioQuizQuestion::getChapterName, newName));
            if (targetExists > 0) {
                log.info("章节改名合并 | 教材={} | {} -> {}（目标已存在 {} 题）",
                        textbookName, oldName, newName, targetExists);
            }
        }

        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName)
                .set(StudioQuizQuestion::getChapterName, newName);
        if (request.getChapterSeq() != null) {
            update.set(StudioQuizQuestion::getChapterSeq, request.getChapterSeq());
        }
        return this.baseMapper.update(null, update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveChapter(QuizChapterMoveRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String fromTextbook = request.getFromTextbook().trim();
        String chapterName = request.getChapterName().trim();
        String toTextbook = request.getToTextbook().trim();

        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, fromTextbook)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, fromTextbook)
                .eq(StudioQuizQuestion::getChapterName, chapterName)
                .set(StudioQuizQuestion::getTextbookName, toTextbook);
        // 章节顺序是「教材内」的概念：换了教材就得用新教材的顺序值，
        // 否则排序会错乱（沿用原值也是一种选择，由调用方显式传入）
        if (request.getToTextbookSeq() != null) {
            update.set(StudioQuizQuestion::getTextbookSeq, request.getToTextbookSeq());
        }
        return this.baseMapper.update(null, update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteChapter(QuizChapterDeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String textbookName = request.getTextbookName().trim();
        String chapterName = request.getChapterName().trim();

        List<StudioQuizQuestion> questions = this.list(new LambdaQueryWrapper<StudioQuizQuestion>()
                .select(StudioQuizQuestion::getId)
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(questions.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        List<Long> ids = questions.stream().map(StudioQuizQuestion::getId).toList();
        removeWrongEntries(ids);

        int deleted = 0;
        for (Long id : ids) {
            if (this.removeById(id)) {
                deleted++;
            }
        }
        log.info("整章删除 | 教材={} | 章节={} | 删除 {} 题", textbookName, chapterName, deleted);
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameTextbook(QuizTextbookRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "教材不存在");

        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, oldName)
                .set(StudioQuizQuestion::getTextbookName, newName);
        if (request.getTextbookSeq() != null) {
            update.set(StudioQuizQuestion::getTextbookSeq, request.getTextbookSeq());
        }
        return this.baseMapper.update(null, update);
    }

    // ==================== 私有：校验与转换 ====================

    /**
     * 跨字段校验 + 规范化（新增 / 修改 / 导入三条入口的唯一校验点）
     *
     * 校验顺序刻意如此：先题型 → 再选项结构 → 再正确项 → 最后题干/解析。
     * 这样报错信息总是「最根本的那个问题」——例如题型非法时不会先报「选项不对」，
     * 因为题型决定选项数下限，顺序反了会给出误导性的提示。
     *
     * @param type 题型原始值（可能是非法值）
     * @param stem 题干原始值
     * @param options 选项列表（已转 VO）
     * @param correctLabels 正确标签原始值（未规范化）
     * @param analysis 解析原文，可为 null / 空串（空串会转成 null）
     * @return 规范化后的完整题目数据
     * @throws BusinessException 任一规则不满足时抛出（A0401）
     */
    private NormalizedQuestion validateAndNormalize(int type, String stem, List<QuizOptionVO> options,
                                                    String correctLabels, String analysis) {
        // ① 题型：闭集，非法值必须报错而不是默认成单选
        QuizQuestionTypeEnum questionType = QuizQuestionTypeEnum.of(type);
        ThrowUtils.throwIf(questionType == null, ErrorCode.PARAMS_ERROR,
                "题型取值非法，合法值为 " + QuizQuestionTypeEnum.valuesText());

        // ② 选项结构：数量在范围内、标签从 A 连续、正文非空
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
        // 标签必须从 A 开始连续：A、B、D 跳号会在界面上显示断档，
        // 用户会以为渲染坏了；而且正确答案 "ABD" 很难在复盘时对上
        ThrowUtils.throwIf(!labels.equals(QuizLabelUtils.sequentialLabels(labels.size())),
                ErrorCode.PARAMS_ERROR,
                "选项标签必须从 A 开始连续，实际为 " + String.join("、", labels));

        // ③ 正确项：先查非法字符与重复，再按题型校验个数
        Character invalid = QuizLabelUtils.findInvalidLabel(correctLabels);
        ThrowUtils.throwIf(invalid != null, ErrorCode.PARAMS_ERROR,
                "正确答案包含非法字符或重复标签：" + invalid);
        String normalizedCorrect = QuizLabelUtils.normalize(correctLabels);
        ThrowUtils.throwIf(normalizedCorrect.isEmpty(), ErrorCode.PARAMS_ERROR, "正确答案不能为空");
        ThrowUtils.throwIf(!labels.containsAll(QuizLabelUtils.toLabelList(normalizedCorrect)),
                ErrorCode.PARAMS_ERROR, "正确答案必须是已有选项");

        if (questionType == QuizQuestionTypeEnum.SINGLE) {
            // 单选题 2 个正确项，几乎一定是题型标错。在录题时就拦住，
            // 比事后看统计「这道单选正确率 40%」再回头查要便宜得多
            ThrowUtils.throwIf(normalizedCorrect.length() != 1, ErrorCode.PARAMS_ERROR,
                    "单选题只能有 1 个正确项");
        } else {
            // 多选题只有 1 个正确项，等于「单选标成了多选」；
            // 而且 2 个选项全对等于「全选就得分」，不是多选
            ThrowUtils.throwIf(normalizedCorrect.length() < 2, ErrorCode.PARAMS_ERROR,
                    "多选题至少要有 2 个正确项");
        }

        // ④ 题干与解析
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

    /**
     * 判定相关变更检测
     *
     * 三件事算「判定相关」：正确答案变了、题型变了、选项标签集合变了。
     * 只改选项文字（标签集合不变）不算——「你当时选了 C」仍然指向同一个选项。
     *
     * @param existing 数据库里的旧值
     * @param normalized 校验后的新值
     * @return true 表示需要重置错题连对进度
     */
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

    /**
     * 重置某题所有用户错题条目的连对进度
     *
     * 只重置 streak，不动 status：
     * 已消解的题不该因为管理员改了个答案就退回待复习——用户曾经掌握过这件事仍然成立；
     * 而「连对几次」的证据确实作废了，必须归零。
     *
     * 用一条 UPDATE 批量完成（不逐条查改）：错题条目可能有几百条，
     * 逐条更新会是一次明显的慢操作。
     */
    private void resetWrongStreak(long questionId) {
        LambdaUpdateWrapper<StudioQuizWrong> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizWrong::getQuestionId, questionId)
                .set(StudioQuizWrong::getCorrectStreak, 0);
        int affected = studioQuizWrongMapper.update(null, update);
        log.info("题目判定相关变更，已重置错题连对进度 | questionId={} | 影响 {} 条", questionId, affected);
    }

    /**
     * 清理指定题目的全部错题条目（逻辑删除）
     *
     * 删除题目 / 整章删除时调用：题目没了，条目无处可去，
     * 留着只会在错题本里渲染出一条「题目已不存在」的空行。
     *
     * @param questionIds 题目 ID 列表
     */
    private void removeWrongEntries(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<StudioQuizWrong> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(StudioQuizWrong::getQuestionId, questionIds);
        studioQuizWrongMapper.delete(wrapper);
    }

    /**
     * 批量填充作答数与正确率
     *
     * 一次 GROUP BY 查完，不是逐行 count——那是标准 N+1。
     * 只统计 result IN (1,2)：跳过不算作答。
     *
     * @param records 待填充的 VO 列表（就地修改）
     */
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
        // 实体是 String（JSON 快照）、VO 是 List，类型不一致：
        // 绝不能用 BeanUtils.copyProperties（会静默跳过该属性）
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

    /**
     * 分页参数兜底纠正
     *
     * 不抛异常：传错页码没必要让整个查询失败（与项目其它模块一致）。
     * 上限用来防「一次拉全表」。
     */
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

    /**
     * 校验并规范化后的题目数据（内部传递用）
     *
     * 做成 record 而不是复用 DTO：它表达的是「已经过校验的、可以直接写库的状态」，
     * 与「用户传来的原始请求」是两回事。混用会让「这字段到底校验过没有」变得模糊。
     */
    private record NormalizedQuestion(QuizQuestionTypeEnum type, String stem, List<QuizOptionVO> options,
                                      String correctLabels, String analysis) {
    }
}

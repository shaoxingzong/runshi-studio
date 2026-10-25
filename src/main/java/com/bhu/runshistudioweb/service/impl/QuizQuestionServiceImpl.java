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
 * 用户此前「连对 1 次」的证据不再成立。但不动 status——
 * 已消解的题不该因为管理员改了个字就退回待复习。
 *
 * 为什么注入两个 Mapper 而不是 Service：
 * 删除题目要清理错题条目、列表要统计作答数，这些是「按条件批量写/聚合读」，
 * 走 Mapper 最直接。反向注入 QuizWrongService 会形成循环依赖
 * （错题服务要读题目表），项目全部使用构造器注入，成环会启动即失败。
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

    // ==================== C 端：教材 / 章节树 ====================

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * <p>为什么这样设计：教材与章节不是独立实体，它们是题目表上的两个名字列
     * （外加各自的排序值）。因此「有哪些教材、每本有哪些章节」只能从题目表里聚合出来。
     * 这里用一条 GROUP BY 一次查完再在内存里挂树，而不是「先查教材、再逐个查章节」——
     * 后者在教材有十几本时就是十几次查询，即典型的 N+1（一次主查询引发 N 次子查询）。
     * 另一个刻意的口径是只统计启用题：用户看到的数字是「这一章有多少题可刷」，
     * 把停用题算进去会让用户点进去发现题数对不上。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 一条 GROUP BY 同时取出教材名、章节名、各自的排序值与题数：
     *     用 MIN 取排序值是因为同一章内不同题目的教材/章节排序值可能不一致，
     *     取最小值相当于「取该章声明过的最靠前位置」；</li>
     *     <li>② 用 LinkedHashMap 按教材名聚合：同一个教材会出现多行（一章一行），
     *     computeIfAbsent 保证每本教材只建一个对象，同时把题数累加进去；</li>
     *     <li>③ 章节的唯一键用「教材名 + 分隔符 + 章节名」：不同教材可以有同名章节，
     *     只用章节名做键会把两本书的「第一章」合并成一个；</li>
     *     <li>④ 全部收集完之后再统一排序：教材按排序值、名称，章节同理。
     *     排序放在最后而不是插入时，是因为排序值要等整本教材的章节都见过才知道。</li>
     * </ol>
     *
     * <p>容易踩的坑：教材名或章节名为 null 的行会被跳过。这类行通常来自手工改库，
     * 跳过它们比让整个目录接口报错更合适——但这也意味着「有题但目录里看不到」，
     * 排查时要先确认题目上的这两个字段是否为空。另一个坑是排序值可能为 null，
     * 因此比较器用了 nullsLast，否则会抛 NullPointerException。
     *
     * @return 教材列表，每本含其章节，按教材排序值与名称升序；
     *         题库为空时返回空列表（不是 null），调用方可直接遍历
     */
    @Override
    public List<QuizTextbookVO> listCatalog() {
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        // ① 用字符串列名 + 聚合：Lambda 版表达不了 MIN/MAX/COUNT。
        // 列名全是硬编码常量，不存在拼接用户输入的注入面
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq", "COUNT(*) AS cnt")
                // 只统计启用题：用户看到的数字是「这一章有多少题可刷」
                .eq("status", STATUS_ENABLED)
                .groupBy("textbook_name", "chapter_name");

        List<Map<String, Object>> rows = this.listMaps(wrapper);

        // ② 用 LinkedHashMap 保留插入顺序，最后再按教材顺序整体排序：
        // 这样「先按教材、再按章节」的排序只需在内存里做一次
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

            // ③ 章节的唯一键是「教材名 + 章节名」：不同教材可以有同名章节
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

        // ④ 排序值可能为 null，必须用 nullsLast，否则比较器会抛空指针
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

    // ==================== 管理端：题目读写 ====================

    /**
     * 管理端题目列表（含答案与解析、作答数与正确率）
     *
     * <p>为什么这样设计：管理端要看的是「存量与质量」，因此这个接口会带上正确答案与解析
     * （供编辑回填），也会带上每题的作答数与正确率（供判断题目难度是否合理）。
     * 作答统计不是逐行 count，而是一次 GROUP BY 批量填充——逐行统计在每页 20 条时
     * 就是 20 次查询，即 N+1（一次主查询引发 N 次子查询）。
     * 另一个细节是：题型与状态是闭集，非法取值必须报错，而不是静默返回空列表。
     * 静默返回空会把排查方向带偏——管理员会以为「这题被删了」，而实际是参数传错了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 请求为 null 时兜一个空对象：不传任何条件等于无条件查第一页，是合理用法；</li>
     *     <li>② 先校验题型与状态：闭集参数的错误必须显式报出，不能靠「查不到数据」暗示；</li>
     *     <li>③ 拼查询条件：教材、章节、题型、状态用等值匹配（走索引），
     *     关键词用 like 模糊匹配题干；</li>
     *     <li>④ 排序按「教材 → 章节 → 题序 → ID」：与索引同向，也让同一章的题连在一起，
     *     管理员翻页时看到的顺序与录入顺序一致；</li>
     *     <li>⑤ 先分页查实体再转 VO：分页必须发生在数据库侧，
     *     不能把所有题查出来再在内存里切页；</li>
     *     <li>⑥ 批量填充作答统计：一次 GROUP BY 查完，与页大小无关；</li>
     *     <li>⑦ 组装 VO 分页对象，把 current / size / total 一并带过去。</li>
     * </ol>
     *
     * <p>容易踩的坑：like 条件用「关键词非空才拼」，而不是把 null 拼进去。
     * 另外关键词没有转义 % 与 _，管理员搜索「100%」时会匹配到多余结果——
     * 这里选择不转义，因为题库题干里出现通配符的概率极低，而转义会让「搜索带百分号的题干」
     * 变得麻烦。若将来题干里这类字符变多，需要在入参侧统一转义。
     *
     * @param request 查询条件（教材、章节、题型、状态、关键词、分页参数），
     *                允许整体为 null（表示无条件查第一页）
     * @return 分页结果，记录里含选项、正确答案与解析；没有匹配数据时 records 为空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题型或状态取值非法时抛 A0401
     */
    @Override
    public Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request) {
        // ① 不传条件 = 无条件查第一页，是合理用法，兜一个空对象即可
        QuizQuestionQueryRequest query = request == null ? new QuizQuestionQueryRequest() : request;

        // ② 题型是闭集：非法取值必须报 A0401，而不是静默返回空列表把排查方向带偏
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
        // ③ 等值条件全部「非 null 才拼」：null 表示不筛选，不能拼成 = null（永远不成立）
        wrapper.eq(query.getTextbookName() != null, StudioQuizQuestion::getTextbookName, query.getTextbookName())
                .eq(query.getChapterName() != null, StudioQuizQuestion::getChapterName, query.getChapterName())
                .eq(query.getType() != null, StudioQuizQuestion::getType, query.getType())
                .eq(query.getStatus() != null, StudioQuizQuestion::getStatus, query.getStatus())
                // 关键词只搜题干：搜解析会让「解析里提过某个词」的题也混进来，管理员难以预期
                .like(StrUtil.isNotBlank(query.getKeyword()), StudioQuizQuestion::getStem, query.getKeyword())
                // ④ 教材 → 章节 → 题序：与 idx_scope 同向，也让「同一章的题连在一起」
                .orderByAsc(StudioQuizQuestion::getTextbookSeq)
                .orderByAsc(StudioQuizQuestion::getTextbookName)
                .orderByAsc(StudioQuizQuestion::getChapterSeq)
                .orderByAsc(StudioQuizQuestion::getChapterName)
                .orderByAsc(StudioQuizQuestion::getQuestionSeq)
                // ID 作为最终排序键：题序可能重复，没有它分页结果会不稳定
                .orderByAsc(StudioQuizQuestion::getId);

        // ⑤ 分页在数据库侧完成，不能查全表再在内存里切页
        Page<StudioQuizQuestion> entityPage = this.page(newPageWithDefaults(query.getCurrent(), query.getPageSize()),
                wrapper);

        List<QuizQuestionAdminVO> records = entityPage.getRecords().stream()
                .map(this::toAdminVO)
                .collect(Collectors.toList());
        // ⑥ 一次 GROUP BY 填完作答数与正确率，查询次数与页大小无关
        fillAnswerStats(records);

        // ⑦ current / size / total 一并带过去，否则前端分页控件会显示成空
        Page<QuizQuestionAdminVO> voPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    /**
     * 管理端题目详情（含答案与解析）
     *
     * <p>为什么这样设计：编辑题目时需要回填完整内容，包括正确答案与解析，
     * 因此这个接口会下发答案——它只对管理员开放，C 端走的是另一个 VO。
     * 与列表接口共用 toAdminVO 与 fillAnswerStats，保证「列表里看到的」与
     * 「详情里看到的」是同一套字段，不会出现「列表有正确率、详情没有」的差异。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先校验 id 为正：负数与 0 不可能存在，早点拒绝省一次查询；</li>
     *     <li>② 查实体并判空：逻辑删除的题查不到，此时给「题目不存在」而不是空对象，
     *     让前端能明确提示；</li>
     *     <li>③ 转 VO 后单独填充统计：详情只有一条记录，但复用同一个批量方法
     *     比另写一套单条统计更不容易出现口径差异。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不做数据权限校验（比如「只能看自己录的题」），
     * 因为题库是公共资产，管理端角色已经是最小权限单位。
     * 若将来要支持多租户，必须在最前面加一层归属判断。
     *
     * @param id 题目 ID；小于等于 0 时抛参数错误
     * @return 题目详情，含选项、正确答案与解析
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法时抛 A0401，
     *         题目不存在（含已逻辑删除）时抛 A0402
     */
    @Override
    public QuizQuestionAdminVO getQuestionById(long id) {
        // ① 负数与 0 不可能存在，早点拒绝省一次查询
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        StudioQuizQuestion question = this.getById(id);
        // ② 逻辑删除的题查不到，给「不存在」而不是空对象，前端才能明确提示
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        QuizQuestionAdminVO vo = toAdminVO(question);
        // ③ 复用批量统计方法：口径与列表完全一致，不会出现两套算法
        fillAnswerStats(List.of(vo));
        return vo;
    }

    /**
     * 新增一道题目：校验通过后以「启用」状态入库，返回新题目的 ID
     *
     * <p>为什么这样设计：题目有四个互相牵制的字段（题型、选项、正确答案、解析），
     * 单独校验任何一个都拦不住错误——「单选却给了 2 个正确项」「正确答案指向不存在的选项」
     * 这类问题只有把字段放在一起看才能发现。因此校验<b>全部</b>收敛到
     * {@link #validateAndNormalize} 这一个方法里，新增 / 修改 / 导入三条入口共用它。
     * 这不是为了少写代码，而是为了避免分叉：一旦某条入口「顺手写个 if」，
     * 就会出现「导入能进、编辑不让改」这种不报错、只让管理员困惑的问题，
     * 而且排查时很难想到是两条路径的规则不一致。
     * 另一个刻意的取舍是新题一律以「启用」状态入库，请求 DTO 里根本没有 status 字段：
     * 若允许新增时直接建一道停用题，管理员会得到一道「建好了但谁也刷不到」的题，
     * 而界面上不会有任何提示。需要停用请走 {@link #updateStatus}。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体。{@code @Valid} 对「整个对象为 null」不生效，
     *     不拦的话后面取值就是空指针，会被兜成 B0001；</li>
     *     <li>② 先把 DTO 的选项转成 VO，再交给统一校验。
     *     转换必须在校验之前——校验方法认的是 VO 类型，而且它会把选项标签统一成大写、
     *     内容去掉首尾空白，这些规范化结果必须体现在入库数据上；</li>
     *     <li>③ 组装实体。教材名与章节名在这里 trim，其余字段为 null 时用默认值兜底
     *     （顺序值 0、难度 1）；选项列表要序列化成 JSON 字符串才能存进 text 列，
     *     编解码统一走 {@code QuizOptionCodec}，避免两处各写一套格式；</li>
     *     <li>④ 落库并返回自增回来的主键。{@code save} 返回 false 说明数据库写入失败，
     *     必须显式报错——不报的话调用方会拿到一个 ID 为 null 的「成功」响应。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法带 {@code @Transactional}，但实际只有一次 insert。
     * 加事务不是为了这一次写，而是为了与「修改」保持同一种写法——
     * 将来若新增时也要顺带做第二张表的写（例如预生成统计行），
     * 忘了加事务就会留下半截数据。反过来也要注意：事务内不能放外部调用，
     * 否则一次几秒的 HTTP 会把数据库连接一直占着。
     * 另一个坑是教材与章节并不是独立实体，它们只是题目表上的两个名字列——
     * 所以新增题目时「章节不存在」这种事根本无从校验，章节是随题目诞生的。
     *
     * @param request 新增请求，不能为 null；教材名、章节名、题干、题型、选项、
     *                正确答案必填，选项 2~6 个（多选至少 3 个），标签必须从 A 连续，
     *                正确项必须都属于已有选项；解析与题源选填
     * @return 新题目的 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         任一跨字段规则不满足（A0401）、入库失败（B0002）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addQuestion(QuizQuestionAddRequest request) {
        // ① @Valid 不覆盖「整个请求体为 null」，不拦会变成空指针
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // ② 先转 VO 再校验：校验方法会把标签转大写、内容 trim，规范化结果要落到入库数据上
        // 编解码统一走 QuizOptionCodec，避免两处各写一套 JSON 格式
        List<QuizOptionVO> options = toOptionVOs(request.getOptions());
        NormalizedQuestion normalized = validateAndNormalize(
                request.getType(), request.getStem(), options, request.getCorrectLabels(),
                request.getAnalysis());

        // ③ 组装实体：只有校验后的值才写库，教材/章节名在这里 trim
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

        // ④ save 返回 false 说明写库失败，必须显式报错，否则调用方会拿到 ID 为 null 的「成功」
        boolean saved = this.save(question);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "新增题目失败，数据库异常");
        // save 成功后 MyBatis-Plus 会把雪花 ID 回填到实体
        return question.getId();
    }

    /**
     * 修改题目（部分更新）：用旧值补齐后整体校验，判定相关变更时重置错题连对进度
     *
     * <p>为什么这样设计：本方法有两个反直觉但必须保持的设计。
     * 第一，<b>「用旧值补齐后整体校验」</b>，而不是只校验请求里传了的字段。
     * 校验规则是跨字段的（单选恰好 1 个正确项、正确项必须属于选项、标签从 A 连续），
     * 只拿请求里的字段去校验，最常见的操作「只改解析」就会因为没传选项
     * 而报「选项不能为空」——这是纯粹的误伤。因此实现固定为：
     * 取旧实体 → 用请求中非 null 的字段覆盖 → 补齐成一份完整状态 → 走与新增同一套校验。
     * 这样「只改解析」和「改答案」走的是同一条代码路径，不会有两套规则。
     * 第二，<b>改答案 / 改题型 / 增删选项要重置错题本的连对进度，但不动 status</b>。
     * 判定依据变了，用户此前「连对 1 次」的证据就不再成立，必须作废；
     * 但 status 记录的是「这道题是否已消解」，用户确实掌握过这件事仍然成立——
     * 管理员改个错别字就把它退回待复习，等于否定了用户的努力。
     * 作废证据、保留结论，这是两件事。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用旧值补齐：请求里为 null 的字段一律沿用旧值。
     *     这是「部分更新」能通过整体校验的前提，也是本方法最容易被写错的一步；</li>
     *     <li>② 整体校验，与新增共用同一个方法。顺序必须在第 ③ 步之前——
     *     校验会产出规范化后的值，而变更检测要拿它和旧值比；</li>
     *     <li>③ 检测「判定相关变更」。这一步<b>必须</b>在覆盖旧值之前完成，
     *     否则比较时新值已经等于旧值，永远判不出变化；</li>
     *     <li>④ 组装白名单字段并写库。这里是服务端写死的白名单，
     *     绝不能把 DTO 直接转成实体整体更新——那样前端能顺手改 deletedAt 与审计字段；</li>
     *     <li>⑤ 解析 / 题源传空串表示「清空」，需要额外一条显式 UPDATE。
     *     因为 updateById 默认跳过 null 字段，不补这一条的话用户永远清不掉写错的解析；</li>
     *     <li>⑥ 判定相关变更时重置该题所有错题条目的连对进度（只重置 streak，不动 status）。</li>
     * </ol>
     *
     * <p>容易踩的坑：<b>不能把 updateById 返回 0 行受影响当成失败。</b>
     * MySQL 默认协议下「SET 的值与原值完全相同」会返回 0 行受影响，
     * 而「管理员打开编辑框、什么都没改、点保存」是极常见的操作——
     * 把它当失败会让用户看到一条莫名其妙的「更新失败」。
     * 存在性已在第 ① 步用 getById 校验过，因此 0 行受影响是安全的 no-op。
     * 另一个坑是「只改选项文字」不算判定变更：标签集合没变，
     * 说明「提交时选了 C」仍指向同一个选项，重置进度反而会误伤用户。
     *
     * @param request 修改请求，不能为 null；id 必填且为正数；
     *                其余字段为 null 表示不修改，解析与题源传空串表示清空；
     *                选项一旦传了就是整批替换，不支持「只改其中一个」
     * @return true 表示保存动作已执行；注意它<b>不代表数据真的变了</b>——
     *         什么都没改时也是 true，调用方不要用它判断「有没有变化」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体或 id 为 null（A0401）、
     *         题目不存在（A0402）、补齐后的整体校验不通过（A0401）时抛出
     */
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

        // ③ 判定相关变更检测：必须在覆盖旧值之前算，否则比较时永远相等
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
        // MySQL 默认协议下「SET 的值与原值完全相同」会返回 0 行受影响，
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
        //    status 保持不变——已消解的不退回待复习（用户确实掌握过，
        //    管理员改题不该否定这一点）；只把「连对几次」的证据作废
        if (verdictChanged) {
            resetWrongStreak(existing.getId());
        }
        return true;
    }

    /**
     * 删除一道题目：逻辑删除题目，并在同一事务里清掉它对应的错题条目
     *
     * <p>为什么这样设计：题目被删掉之后，错题本里的条目就无处可去了——
     * 留着它只会在页面上渲染出一条「题目已不存在」的空行，用户点进去什么也看不到。
     * 但<b>作答记录必须保留</b>：统计只读 result 列，不需要题目内容，
     * 删掉会让用户的累计作答数凭空减少，那是一种很难解释的数据丢失。
     * 两次写在同一事务里，顺序是「先清条目、再删题目」，这个顺序不能换：
     * 反过来的话，题目删除失败会留下一批「条目指向不存在的题」的脏数据，
     * 而这种脏数据不会报错，只会在错题本里静默地少几条或渲染出空行。
     * 另外这里用的是逻辑删除（写 deleted_at，行仍在库里），
     * 因此「误删可恢复」这个前提成立——不要在业务代码里改成物理删除。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正并查实体确认存在。负数与 0 不可能存在，早点拒绝省一次查询；
     *     不存在时给 A0402 而不是返回 0——这样前端能区分「删掉了」与「本来就没有」；</li>
     *     <li>② 先清错题条目。必须排在删除之前，理由见上：顺序反了会留下悬空引用；</li>
     *     <li>③ 逻辑删除题目并把结果转成 1 / 0。作答记录刻意不动。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是 int 而不是 boolean，因为实现里把「删除成功 = 1、失败 = 0」
     * 显式化了。但要注意：存在性已在第 ① 步校验过，所以返回 0 只可能是并发删除
     * （在你查完之后、删之前，别人把它删了）——这不是错误，调用方不该为此弹报错。
     * 另一个坑是「清错题条目」用的是逻辑删除，所以条目行也还在库里，
     * 排查「为什么错题本里这条不见了」时要去查它的 deleted_at，而不是断定它没被写过。
     *
     * @param id 题目 ID，必须大于 0；小于等于 0 时抛 A0401
     * @return 实际删除的题目条数（1 或 0）；0 表示该行在查出来之后已被并发删除
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法（A0401）、
     *         题目不存在或已被逻辑删除（A0402）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteQuestion(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // ① 先确认存在：不存在时给 A0402，前端才能区分「删掉了」与「本来就没有」
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // ② 同一事务内先清错题条目，再逻辑删除题目：
        // 反过来的话，题目删除失败会留下「条目指向不存在的题」的脏数据
        removeWrongEntries(List.of(id));

        // ③ 作答记录保留：统计只读 result，不需要题目内容
        return this.removeById(id) ? 1 : 0;
    }

    /**
     * 批量删除题目：逐条清错题条目并逻辑删除，某条不存在时跳过而不中断
     *
     * <p>为什么这样设计：本方法的语义是「把这些都删掉」，因此<b>其中一条已被别人删掉
     * 不该让整批失败</b>——批量操作里「有人抢先删了某一条」是常态，
     * 为它让整批失败会让管理员反复重试却永远成功不了，而他根本看不出是哪一条有问题。
     * 因此不存在的条目直接跳过，最后返回实际删除条数。
     * 但整批仍然在同一个事务里：要么全删、要么全不删，
     * 不会出现「删了一半、另一半因为某条异常而留在库里」这种半成品状态。
     * 另一个取舍是逐条删除而不是一条批量 UPDATE：因为每条都要顺带清错题条目，
     * 而清理本身是按 ID 列表批量做的，两次批量操作的粒度不同，
     * 硬合成一条 SQL 反而更难读、也更难在出错时定位。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空列表：一条都没选就没什么可删的，这属于参数问题而不是「删了 0 条」；</li>
     *     <li>② 去重。前端勾选时可能出现重复 ID（分页切换后选中状态串了），
     *     不去重会让同一条被处理两次——第二次虽然会因查不到而跳过，
     *     但白跑了一次查询，而且如果将来改成批量 SQL，重复 ID 会带来更麻烦的问题；</li>
     *     <li>③ 逐条跳过 null 与非正数：列表来自前端，不能假定每个元素都合法；</li>
     *     <li>④ 查不到就跳过，不抛异常（理由见上）。查得到才清条目 + 逻辑删除并计数；</li>
     *     <li>⑤ 返回实际删除条数。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值是「实际删掉的条数」，<b>既不等于入参长度，也不等于去重后的长度</b>
     * ——前端若用它显示「成功删除 N 题」，必须接受 N 可能小于勾选数。
     * 另一个坑是别把「跳过不存在的」理解成「吞掉了错误」：
     * 真正的数据库错误（连接断了、约束冲突）仍然会抛出去并让整批回滚，
     * 跳过的只是「这条本来就不存在」这一种预期内的情况。
     *
     * @param ids 题目 ID 列表，不能为 null 或空（否则抛 A0401）；
     *            列表里可以含 null 与非正数，它们会被静默跳过
     * @return 实际删除条数，可能为 0（全部都不存在时）；永不为负数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 列表为 null 或空时抛 A0401；
     *         数据库层面的异常会原样抛出让整批回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteQuestions(List<Long> ids) {
        ThrowUtils.throwIf(ids == null || ids.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择要删除的题目");

        // ② 去重后再删：前端勾选时可能出现重复 id（分页切换后选中状态串了）
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        int deleted = 0;
        for (Long id : distinctIds) {
            // ③ 列表来自前端，不能假定每个元素都合法：null 与非正数直接跳过
            if (id == null || id <= 0) {
                continue;
            }
            // ④ 某条不存在时不中断：批量删除的语义是「把这些都删掉」，
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

    /**
     * 启用 / 停用一道题：只改 status 一列，其它数据一概不动
     *
     * <p>为什么这样设计：停用是<b>可逆的下线</b>，不是删除。停用之后这道题
     * 不再被抽到、也不再出现在错题本里，但历史作答与错题条目全都保留，
     * 重新启用后一切自动回来。因此这里刻意<b>不去清理任何关联数据</b>——
     * 一旦停用就删条目，用户重新启用后错题记录会永久消失，而管理员根本不会想到
     * 「下线一下」会造成数据丢失。
     * 另一个取舍是它<b>不加事务</b>：本方法只有一次单行 UPDATE，
     * 没有第二次写需要与它保持一致。加事务只会多一次提交往返，
     * 而「有没有事务」这件事一旦与「写了几张表」脱钩，读代码的人就得每次都去数一遍。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验 id 为正。负数与 0 不可能存在，早点拒绝省一次查询；</li>
     *     <li>② 校验状态落在闭集 {0, 1} 内。状态非法必须报错，
     *     静默当成启用会让管理员以为下线成功了，而实际上什么都没改；</li>
     *     <li>③ 查实体确认存在，不存在给 A0402——否则下面更新 0 行，
     *     调用方只看到 false，分不清是「不存在」还是「并发删除」；</li>
     *     <li>④ 只 set id 与 status 两个字段后更新。其余字段保持 null，
     *     MyBatis-Plus 会跳过它们，因此不会把别的列覆盖成空。</li>
     * </ol>
     *
     * <p>容易踩的坑：状态取值是 <b>0-启用、1-停用</b>，与「true 表示启用」的直觉相反。
     * 传反了会把整章题下线，而界面不会有任何异常——这是本模块最容易犯的错。
     * 另一个反直觉点是：已经发给用户的批次里的停用题<b>仍然可以提交作答</b>。
     * 此时拒绝会让他卡在一道永远答不了的题上，所以停用只应影响「后续抽题」与
     * 「错题本展示」，不能反过来让进行中的作答失败。
     *
     * @param id 题目 ID，必须大于 0；小于等于 0 时抛 A0401
     * @param status 目标状态：0-启用，1-停用；其它值抛 A0401
     * @return true 表示更新成功；false 表示该行在查出来之后已被并发删除
     *         （存在性已在前一步校验过，因此 false 不是「参数错」）
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 非法或状态非法（A0401）、
     *         题目不存在（A0402）时抛出
     */
    @Override
    public boolean updateStatus(long id, int status) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "题目 id 不合法");
        // ② 状态是闭集：非法值必须报错，静默当成启用会让管理员以为下线成功了
        ThrowUtils.throwIf(status != STATUS_ENABLED && status != STATUS_DISABLED,
                ErrorCode.PARAMS_ERROR, "状态只能是 0（启用）或 1（停用）");
        StudioQuizQuestion existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "题目不存在");

        // ④ 停用只改 status：不动错题条目，因此重新启用后条目自动回来（可逆）
        StudioQuizQuestion update = new StudioQuizQuestion();
        update.setId(id);
        update.setStatus(status);
        return this.updateById(update);
    }

    /**
     * 批量导入题目：先把整段文本解析成题目，全部合法才逐条入库
     *
     * <p>为什么这样设计：本方法的核心约定是<b>整批全成功或全失败</b>——
     * 任何一条解析或校验失败，整批都不落库。这是刻意的取舍：
     * 「导入了一半」会让管理员无法判断哪些进了、哪些没进，只能全部删掉重来，
     * 而删的时候又很难精确挑出刚导入的那批（它们和旧题混在同一个章节里）。
     * 代价是一次导入的错误要一次性看完，因此返回值里带逐行错误与行号。
     * 第二个关键设计是 <b>dryRun 预览</b>：前端先传 dryRun=true 拿到解析结果与
     * 全部错误行号，展示给管理员确认，确认后再传 false 真正落库。
     * 这样「导到第 12 行才发现错」的尴尬就不会发生——而且即使真导，
     * 也是整批全成或全不成。dryRun 与真实导入共用同一个返回结构，
     * 前端因此可以用同一套渲染逻辑做「预览」与「结果」。
     * 第三个设计是逐条走与新增<b>完全相同</b>的校验方法（{@link #validateAndNormalize}）：
     * 解析器只负责语法，跨字段语义必须与手工新增共用一套规则，
     * 否则会出现「导入能进、编辑不让改」这类不报错、只让人困惑的问题。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体，把文本交给解析器逐行解析。解析器不碰数据库，纯函数；</li>
     *     <li>② 初始化结果对象并收齐<b>解析阶段</b>的错误（格式不对、字段缺失）。
     *     这些错误的行号直接对应粘贴文本的物理行，管理员照着改即可；</li>
     *     <li>③ 校验题目总数是否超过上限。超了记一条行号为 0 的错误——
     *     0 表示「这是整批级别的问题，不属于某一行」，前端要单独渲染；</li>
     *     <li>④ 逐条走与新增相同的跨字段校验，失败的行号与原因收进错误列表。
     *     这里用 try-catch 捕获 BusinessException 而不是让它抛出去：
     *     一条坏行不该让整份文件白导，要尽量把问题一次报全；</li>
     *     <li>⑤ 只要有任何错误就整批不落库，直接返回结果（此时 imported 为 0）；</li>
     *     <li>⑥ dryRun 为 true 时同样只校验不落库，前端据此做预览；</li>
     *     <li>⑦ 全部合法才逐条入库。题序从 startSeq 起依次 +1，
     *     这样「先导第一章 0-49、再导第二章 50-99」可以一次排好。</li>
     * </ol>
     *
     * <p>容易踩的坑：dryRun 时 imported <b>恒为 0</b>，因此判断「这批能不能导」
     * 要看 errors 是否为空，而不是看 imported——拿 imported 判断会把
     * 「预览通过」误读成「什么都没导进去」。
     * 另一个坑是本方法带事务，整批 insert 在同一个事务里：
     * 一次导入两千题会长时间持有事务与数据库连接，这期间其它写操作可能被阻塞。
     * 它不是「可以随便放到后台任务里反复调」的接口，真要处理超大题库应当先切分再分批导入。
     * 还有一点：教材名与章节名在这一层 trim，导入的题会全部归到同一章下，
     * 不存在「一条题一个章节」的用法。
     *
     * @param request 导入请求，不能为 null；教材名、章节名、待解析文本必填
     *                （由 DTO 上的 {@code @NotBlank} 拦下）；
     *                dryRun 为 null 时按 false（真导入）处理；
     *                startSeq 为 null 时按 0 处理
     * @return 导入结果：parsed 是解析出的题目数，imported 是实际入库数
     *         （dryRun 或存在错误时为 0），errors 是逐行错误（为空表示整批合法）；
     *         永不为 null。注意：单行内容不合法<b>不会</b>抛异常，
     *         而是以错误明细的形式出现在返回值里
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         入库时数据库写入失败（B0002）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuizImportResultVO importQuestions(QuizQuestionImportRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // ① 解析器只做语法校验，不碰数据库
        QuizImportParser.ParseResult parsed = QuizImportParser.parse(request.getText());
        QuizImportResultVO result = new QuizImportResultVO();
        result.setDryRun(Boolean.TRUE.equals(request.getDryRun()));
        result.setParsed(parsed.questions().size());
        result.setImported(0);
        result.setErrors(new ArrayList<>());

        // ② 解析阶段的错误先收齐（行号对应粘贴文本的物理行）
        for (QuizImportParser.LineError error : parsed.errors()) {
            result.getErrors().add(new QuizImportResultVO.ImportError(error.line(), error.message()));
        }

        // ③ 整批级别的错误行号记 0：它不是某一行的问题，前端要单独渲染
        if (parsed.questions().size() > QuizConstant.IMPORT_MAX_QUESTIONS) {
            result.getErrors().add(new QuizImportResultVO.ImportError(0,
                    "单次最多导入 " + QuizConstant.IMPORT_MAX_QUESTIONS + " 道题，本次 " + parsed.questions().size() + " 道"));
        }

        // ④ 逐条走与新增相同的校验；单条失败只记录不抛出，才能把问题一次报全
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

        // ⑤ 有任何错误：整批不落库（「导入了一半」会让管理员无法判断哪些进了）
        if (!result.getErrors().isEmpty()) {
            return result;
        }
        // ⑥ 预览模式：校验全过也不落库，imported 保持 0
        if (Boolean.TRUE.equals(request.getDryRun())) {
            return result;
        }

        // ⑦ 全部合法才逐条入库；normalizedList 与 parsed.questions() 按下标一一对应
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
        return result;
    }

    // ==================== 管理端：章节 / 教材批量操作 ====================

    /**
     * 管理端章节列表：一条 GROUP BY 聚合出「每本教材的每一章有多少题、其中启用几题」
     *
     * <p>为什么这样设计：教材与章节不是独立实体，它们只是题目表上的两个名字列
     * （外加各自的排序值），因此「有哪些章节」只能从题目表里聚合出来。
     * 这里用一条 GROUP BY 一次查完，而不是「先查教材、再逐个查章节」——
     * 后者在教材有十几本时就是十几次查询，即典型的 N+1（一次主查询引发 N 次子查询）。
     * 更关键的是<b>统计口径与 C 端不同</b>：C 端只数启用题（用户看到的是「能刷多少」），
     * 管理端要数全部题（管理员看到的是「这一章有多少存量」），
     * 并额外给出「其中启用了几题」。因此这里没有复用 C 端的 VO——
     * 两端共用一个 VO 的话，必然有一端拿到的数字是错的，而这种错不会报错、只会让人困惑。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 一条 GROUP BY 同时取章节题数与启用题数：用
     *     {@code SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END)} 在同一个查询里算出启用数，
     *     而不是查完再逐章发一次 count；</li>
     *     <li>② 逐行转 VO。教材名或章节名为 null 的脏数据行跳过——
     *     这类行来自手工改库，跳过比让整个列表接口报错合适，
     *     但这也意味着「有题却不在列表里」，排查时先确认题目上这两个字段是否为空；</li>
     *     <li>③ 最后统一排序。放在循环之后是因为排序依赖已经填好的 VO 字段；
     *     按「教材顺序 → 教材名 → 章节顺序 → 章节名」四级排序，
     *     保证两次请求的顺序一致（只按顺序值排的话，相同顺序值的章节顺序会随数据库返回顺序漂移）。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的是<b>扁平的章节列表，不是嵌套的树</b>——每一行都带教材名，
     * 前端要按教材分组得自己聚合。这样设计是因为管理端的表格是扁平的，
     * 嵌套结构反而要多一层展开逻辑。
     * 另一个坑是聚合结果的数值类型：不同数据库驱动下 {@code COUNT} 可能返回
     * BigInteger 而不是 Long/Integer，因此取值必须走 {@code asInt} 这类
     * 「先判 Number 再转换」的辅助方法，直接强转会抛 ClassCastException。
     *
     * @return 章节列表，每行含教材名、教材顺序、章节名、章节顺序、总题数与启用题数；
     *         题库为空时返回空列表，不返回 null
     */
    @Override
    public List<QuizChapterAdminVO> listChaptersForAdmin() {
        // ① 一条 GROUP BY 同时算总题数与启用题数：不逐章 count，避免 N+1
        QueryWrapper<StudioQuizQuestion> wrapper = new QueryWrapper<>();
        // 管理端口径：含停用题（管理员要看存量），另外单独给启用数
        wrapper.select("textbook_name", "MIN(textbook_seq) AS textbook_seq",
                        "chapter_name", "MIN(chapter_seq) AS chapter_seq",
                        "COUNT(*) AS cnt",
                        "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS enabled_cnt")
                .groupBy("textbook_name", "chapter_name");

        List<QuizChapterAdminVO> result = new ArrayList<>();
        // ② 逐行转 VO；教材名或章节名为 null 的脏数据行直接跳过
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
        // ③ 四级排序保证两次请求顺序一致（只按顺序值排时，相同值之间顺序不确定）
        result.sort(Comparator.comparing(QuizChapterAdminVO::getTextbookSeq)
                .thenComparing(QuizChapterAdminVO::getTextbookName)
                .thenComparing(QuizChapterAdminVO::getSeq)
                .thenComparing(QuizChapterAdminVO::getName));
        return result;
    }

    /**
     * 章节改名（整章批量改名，可顺带调序）
     *
     * <p>为什么这样设计：章节没有独立表，它就是题目上的两个名字列，
     * 因此「改名」本质上是一条批量 UPDATE。逐条更新既慢，又会中途失败留下半章
     * （一部分题已经改了名、另一部分还是旧名，界面上会出现两个半截章节）。
     * 第二个关键取舍是<b>允许改成与另一章同名</b>，此时两章自然合并成一章，不报错。
     * 理由很实际：管理员想把两章合成一章时，若这里报「名称已存在」，
     * 他只能先把目标章改成别的名字、再改回来，多两步且容易改错；
     * 而他想的大概率就是合并。代码里只记一条 info 日志留痕，不做拦截。
     * 第三个取舍是「改名为自己」被允许：此时这条 UPDATE 变成「只调序」，
     * 是一个有意义的操作（只想改章节顺序），因此不能把它当成空操作拒掉。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体并 trim 三个名字参数。trim 必须在查询之前——
     *     否则「 第一章」与「第一章」会被当成两个不同的章节，查不到数据；</li>
     *     <li>② 先数一下原章节是否存在。不存在时给 A0402，而不是让 UPDATE 影响 0 行
     *     后被误认为成功——这两种情况在前端看起来都是「没变化」，但原因完全不同；</li>
     *     <li>③ 若新旧名字不同且目标章节已存在，记一条 info 日志说明发生了合并。
     *     合并本身不需要额外代码（UPDATE 之后自然就是一章），
     *     但这条日志是事后排查「这一章怎么突然多出几十道题」的唯一线索；</li>
     *     <li>④ 一条 UPDATE 改章节名；chapterSeq 传了才一并改，
     *     没传就保持原值——「只改名不动顺序」是更常见的诉求。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值是<b>受影响的题目行数，不是章节数</b>。
     * 一章可能有几百道题，前端不要拿它当「改了几个章节」显示。
     * 另一个坑是合并的副作用：两个章节合并后，它们的题序会混在一起
     * （两章各自带着自己的 questionSeq），若需要严格的顺序，得再逐题调整——
     * 本方法不处理这件事，因为它只改章节维度。
     * 还有一点：这条 UPDATE 会同时改停用题，这是对的——
     * 章节名是题目的属性，不该因为题目停用就留在旧名字下。
     *
     * @param request 改名请求，不能为 null；教材名、原章节名、新章节名必填
     *                （由 DTO 上的 {@code @NotBlank} 拦下）；chapterSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该章题目数，含停用题）。
     *         注意「改成与原名相同、且不传新顺序」时 MySQL 会返回 0——
     *         那是「没有任何一行真的变了」的 no-op，不是失败；
     *         章节不存在时抛异常，因此不会与这种 0 混淆
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         章节不存在（A0402）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameChapter(QuizChapterRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ① trim 必须在查询之前：否则「 第一章」与「第一章」会被当成两个章节
        String textbookName = request.getTextbookName().trim();
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        // ② 先确认章节存在：不存在时给 A0402，而不是让 UPDATE 影响 0 行后被误认为成功
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        // ③ 改名到已存在的章节时合并：不报错。用户想的大概率就是合并，
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

        // ④ 一条 UPDATE 改整章；chapterSeq 传了才改，没传保持原值（「只改名不动顺序」更常见）
        LambdaUpdateWrapper<StudioQuizQuestion> update = new LambdaUpdateWrapper<>();
        update.eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, oldName)
                .set(StudioQuizQuestion::getChapterName, newName);
        if (request.getChapterSeq() != null) {
            update.set(StudioQuizQuestion::getChapterSeq, request.getChapterSeq());
        }
        return this.baseMapper.update(null, update);
    }

    /**
     * 移动章节：把整章挪到另一本教材下，可顺带改教材顺序值
     *
     * <p>为什么这样设计：典型场景是导入时把章节放错了教材，或者一本教材要拆成两本。
     * 与改名一样，它是一条按「教材名 + 章节名」定位的批量 UPDATE——
     * 章节不是实体，没有 ID 可以按主键更新，只能靠这两个名字列定位。
     * 一个刻意的取舍是<b>不校验目标教材是否存在</b>：教材同样只是题目上的名字列，
     * 「移到一本还不存在的教材」在数据上完全合法，实际效果就是新建了那本教材。
     * 若在这里要求目标教材必须已存在，就永远没法「先有章节再有教材」，
     * 而这恰恰是拆分教材时的常见操作。前端应当在选择框里只给已有教材，
     * 但后端不拦——这是把「选择体验」与「数据合法性」分开的典型做法。
     * 另一个取舍是目标教材下已有同名章节时<b>合并而不报错</b>：
     * 报错会让用户不得不先把两章改成不同名字再移，而他想的大概率就是合并。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体并 trim 三个名字参数。trim 必须在查询之前，
     *     否则前后空格会让「章节不存在」这种误判凭空出现；</li>
     *     <li>② 校验来源章节存在。不存在时给 A0402——否则 UPDATE 影响 0 行，
     *     调用方会以为「移成功了但这一章本来就没题」；</li>
     *     <li>③ 一条 UPDATE 改教材名；toTextbookSeq 传了才一并改。
     *     为什么顺序值要一起改：章节顺序是「教材内」的概念，
     *     换了教材就该用新教材的顺序值，否则这一章在新教材里的位置会落到意料之外的地方。</li>
     * </ol>
     *
     * <p>容易踩的坑：不传 toTextbookSeq 时会沿用原值，因此章节在新教材里的排序
     * 取决于它原先那本书的顺序值——这经常表现为「移过去之后排到了最后/最前」，
     * 而数据本身完全正确。前端应当把顺序值一并提交。
     * 另一个坑是本方法只改教材名，<b>不改章节名</b>：
     * 目标教材下如果已有同名章节，移过去就是合并，两章的题会混在一起，
     * 且不会有任何提示——需要留痕时得靠调用方自己记日志。
     *
     * @param request 移动请求，不能为 null；来源教材名、章节名、目标教材名必填；
     *                toTextbookSeq 为 null 表示沿用该章当前的教材顺序值
     * @return 受影响的题目行数（即该章题目数，含停用题）；不是「章节数」。
     *         来源与目标都相同（原地移动）时 MySQL 会返回 0，那是 no-op 而不是失败
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         来源章节不存在（A0402）时抛出；目标教材不存在<b>不会</b>抛异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveChapter(QuizChapterMoveRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ① trim 必须在查询之前，否则前后空格会导致「章节不存在」的误判
        String fromTextbook = request.getFromTextbook().trim();
        String chapterName = request.getChapterName().trim();
        String toTextbook = request.getToTextbook().trim();

        // ② 校验来源章节存在，不存在时给 A0402 而不是让 UPDATE 影响 0 行
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, fromTextbook)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        // ③ 只改教材名：目标教材不必已存在（教材只是名字列，移过去就等于新建）
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

    /**
     * 删除整章：逻辑删除该章的全部题目，并在同一事务里清掉它们的错题条目
     *
     * <p>为什么这样设计：这是本模块唯一会批量删数据的接口，因此有几个必须坚持的点。
     * 第一，<b>删的是题目（逻辑删除），不是章节这个「实体」</b>——
     * 章节本来就没有独立记录，删完这一章的所有题，它在列表里自然就不存在了。
     * 第二，<b>错题条目要一并清理</b>：题目没了，条目无处可去，
     * 留着只会在错题本里渲染出一条「题目已不存在」的空行。
     * 第三，<b>作答记录保留</b>：统计只读 result 列，不需要题目内容，
     * 删掉会让用户的累计作答数凭空减少。
     * 第四，<b>章节不存在时抛异常而不是返回 0</b>：这样前端才能区分
     * 「删了 0 题」与「这章根本不存在」，否则用户点删除后什么都没发生，
     * 也看不到任何提示。
     * 两个写操作在同一事务里，顺序是「先清条目、再删题目」——
     * 反过来的话，删除中途失败会留下一批指向不存在题目的条目。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体并 trim 教材名与章节名（trim 必须在查询之前）；</li>
     *     <li>② 先查出该章全部题目的 ID。这里只 select 主键而不是整行：
     *     后面用不到选项、题干这些大字段，查回来只是浪费内存与网络；
     *     列表为空说明章节不存在，给 A0402；</li>
     *     <li>③ 一次性清掉这批题目的错题条目——按 ID 列表批量删，不是逐条；</li>
     *     <li>④ 逐条逻辑删除并计数。这里逐条而不是一条批量 UPDATE，
     *     是为了拿到「实际删掉几条」这个准确的数字；
     *     最后记一条 info 日志，删了多少题必须留痕。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法按「教材名 + 章节名」定位，而不是按章节 ID——
     * 教材章节没有 ID。这意味着若两个章节此前被改名合并成了一章，
     * 这里会把合并后的整章一起删掉，而管理员可能以为只删了其中一部分。
     * 另一个坑是它<b>不会</b>回滚已经发生的统计口径变化：
     * 删完之后「这一章」在管理端列表里消失，但历史作答记录仍在，
     * 因此总作答数不会减少——这是对的，不是数据不一致。
     *
     * @param request 删除请求，不能为 null；教材名与章节名必填
     * @return 实际删除的题目数。查得到章节就一定有题，因此这个值不小于 1；
     *         章节不存在时抛异常，不会返回 0——前端据此可以区分
     *         「删了 0 题」与「这章根本不存在」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         章节不存在或该章已无题（A0402）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteChapter(QuizChapterDeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String textbookName = request.getTextbookName().trim();
        String chapterName = request.getChapterName().trim();

        // ② 只 select 主键：后面用不到题干与选项这些大字段
        List<StudioQuizQuestion> questions = this.list(new LambdaQueryWrapper<StudioQuizQuestion>()
                .select(StudioQuizQuestion::getId)
                .eq(StudioQuizQuestion::getTextbookName, textbookName)
                .eq(StudioQuizQuestion::getChapterName, chapterName));
        ThrowUtils.throwIf(questions.isEmpty(), ErrorCode.NOT_FOUND_ERROR, "章节不存在");

        List<Long> ids = questions.stream().map(StudioQuizQuestion::getId).toList();
        // ③ 一次性清掉这批题目的错题条目：按 ID 列表批量删，不是逐条
        removeWrongEntries(ids);

        // ④ 逐条逻辑删除并计数：需要准确的「实际删掉几条」，因此不用一条批量 UPDATE
        int deleted = 0;
        for (Long id : ids) {
            if (this.removeById(id)) {
                deleted++;
            }
        }
        log.info("整章删除 | 教材={} | 章节={} | 删除 {} 题", textbookName, chapterName, deleted);
        return deleted;
    }

    /**
     * 重命名教材：把整本教材的所有题目改到新名字下，可顺带改教材顺序值
     *
     * <p>为什么这样设计：教材同样没有独立表，它就是题目上的
     * {@code textbook_name} + {@code textbook_seq} 两列，因此「改名」=
     * 一条按旧名字匹配的批量 UPDATE，一次改完整本教材的所有题（含各章节、含停用题）。
     * 这与章节改名是同一套做法，区别只在不带章节维度——
     * 改教材名会把它下面所有章节一起带走，章节名本身不变。
     * 一个刻意的取舍是<b>改到已存在的教材名时自然合并</b>，不报错：
     * 与章节改名同理，管理员想的大概率就是合并，报错只会逼他多做两步。
     * 另一个取舍是<b>顺序值只在显式传入时才改</b>：只改名不动顺序是更常见的诉求，
     * 若每次都按默认值覆盖，管理员精心排好的教材顺序会被一次改名全部打乱。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 拦空请求体并 trim 新旧名字。trim 必须在查询之前——
     *     否则「 第一册」与「第一册」会被当成两本教材，直接走到「教材不存在」；</li>
     *     <li>② 先数一下原教材是否存在，不存在给 A0402。
     *     这一步不能省：否则改名影响 0 行时，接口会返回 0，
     *     而调用方无法区分「这本教材本来就没题」与「名字打错了」；</li>
     *     <li>③ 一条 UPDATE 改教材名；textbookSeq 传了才一并改。
     *     注意这条 UPDATE 没有 LIMIT，影响范围是整本教材。</li>
     * </ol>
     *
     * <p>容易踩的坑：这是<b>全库范围的批量更新</b>，改错一个名字会影响整本教材的所有题目，
     * 而接口不会提示「影响了多少章节、多少题」。前端确认框里应当先展示
     * 「该教材下有 N 章 M 题」，让管理员看清影响面再动手。
     * 另一个坑是改名不会改动章节名：改名后的教材里章节名保持原样，
     * 这是正常现象，不要误以为改名失败。
     * 还有一点：如果新旧名字相同，这条 UPDATE 在 MySQL 默认协议下会返回 0 行受影响，
     * 那是正常的 no-op，不是失败。
     *
     * @param request 改名请求，不能为 null；原教材名与新教材名必填；
     *                textbookSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该教材的题目总数，含停用题）；
     *         新旧名字相同且不传新顺序时 MySQL 会返回 0，那是 no-op 而不是失败；
     *         教材不存在时抛异常，因此不会与这种 0 混淆
     * @throws com.bhu.runshistudioweb.exception.BusinessException 请求体为 null（A0401）、
     *         教材不存在（A0402）时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renameTextbook(QuizTextbookRenameRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // ① trim 必须在查询之前，否则前后空格会直接走到「教材不存在」
        String oldName = request.getOldName().trim();
        String newName = request.getNewName().trim();

        // ② 先确认教材存在：否则影响 0 行时分不清「本来就没题」与「名字打错了」
        long exists = this.count(new LambdaQueryWrapper<StudioQuizQuestion>()
                .eq(StudioQuizQuestion::getTextbookName, oldName));
        ThrowUtils.throwIf(exists == 0, ErrorCode.NOT_FOUND_ERROR, "教材不存在");

        // ③ 一条 UPDATE 改整本教材；textbookSeq 传了才改，避免打乱已排好的顺序
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
     * 只改选项文字（标签集合不变）不算——「提交时选了 C」仍然指向同一个选项。
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
     * 已消解的题不该因为管理员改了个答案就退回待复习——用户确实掌握过这件事仍然成立；
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

package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterDeleteRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterMoveRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizChapterRenameRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionAddRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionImportRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionQueryRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizQuestionUpdateRequest;
import com.bhu.runshistudioweb.model.dto.quiz.QuizTextbookRenameRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizQuestion;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizChapterVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizImportResultVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizQuestionAdminVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizTextbookVO;

import java.util.List;

/**
 * 刷题题库服务（管理端为主，另含 C 端的教材 / 章节树）
 *
 * @author shaoshing
 */
public interface QuizQuestionService extends IService<StudioQuizQuestion> {

    // C 端：教材 / 章节树

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * @return 教材列表（按教材排序值、名称升序），每本含其章节（按章节排序值、名称升序）；题库为空时返回空列表，不返回 null
     */
    List<QuizTextbookVO> listCatalog();

    // 管理端：题目读写

    /**
     * 管理端分页查询题目（含选项与答案、作答数与正确率）
     *
     * @param request 查询条件，允许整体为 null（无条件查第一页）；字段为 null 表示该条件不参与筛选，不是「查 null 值」
     * @return 分页结果，记录里含选项、正确答案与解析；没有匹配数据时 records 为空列表（不是 null）
     * @throws BusinessException 题型或状态取值非法时抛 A0401
     */
    Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request);

    /**
     * 管理端按 id 查题目详情（含选项与答案）
     *
     * @param id 题目 ID，必须大于 0（小于等于 0 抛 A0401）
     * @return 题目详情，含选项、正确答案与解析；永不为 null
     * @throws BusinessException id 非法（A0401）、题目不存在（含已逻辑删除，A0402）
     */
    QuizQuestionAdminVO getQuestionById(long id);

    /**
     * 新增题目
     *
     * @param request 新增请求，不能为 null；教材名、章节名、题干、题型、选项、正确答案必填；选项 2~6 个（多选至少 3 个），标签必须从 A 连续
     * @return 新题目 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws BusinessException 字段不合法或跨字段规则不满足时抛 A0401；入库失败时抛 B0002
     */
    long addQuestion(QuizQuestionAddRequest request);

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * @param request 修改请求，不能为 null；id 必填且为正数；其余字段为 null 表示不修改，解析与题源传空串表示清空
     * @return true 表示保存成功；不代表数据真的变了，什么都没改时也是 true
     * @throws BusinessException 题目不存在（A0402）、校验不通过（A0401）
     */
    boolean updateQuestion(QuizQuestionUpdateRequest request);

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * @param id 题目 ID，必须大于 0
     * @return 实际删除的题目数（1 或 0）
     * @throws BusinessException id 非法（A0401）或题目不存在（A0402）
     */
    int deleteQuestion(long id);

    /**
     * 批量删除题目
     *
     * @param ids 题目 ID 列表，不能为 null 或空（否则抛 A0401）；列表里可以含 null 与非正数，它们会被静默跳过
     * @return 实际删除条数，可能为 0（全部都不存在时）
     * @throws BusinessException 列表为空时抛 A0401
     */
    int batchDeleteQuestions(List<Long> ids);

    /**
     * 启用 / 停用题目
     *
     * @param id 题目 ID，必须大于 0
     * @param status 目标状态：0-启用，1-停用；其它值抛 A0401
     * @return true 表示更新成功；false 表示该行在更新瞬间已不存在（只可能是并发删除）
     * @throws BusinessException id 非法 / 状态非法（A0401）、题目不存在（A0402）
     */
    boolean updateStatus(long id, int status);

    /**
     * 批量导入题目
     *
     * @param request 导入请求，不能为 null；教材名、章节名、待解析文本必填；dryRun 为 null 时按 false（真导入）处理；startSeq 为 null 时按 0 处理
     * @return 导入结果，含解析成功数、实际入库数、是否 dryRun 与逐行错误列表；有错误时 imported 为 0 且整批未落库；永不为 null
     * @throws BusinessException 请求体为 null（A0401）、入库失败（B0002）。单行内容不合法不会抛异常，而是以错误明细的形式出现在返回值里
     */
    QuizImportResultVO importQuestions(QuizQuestionImportRequest request);

    // 管理端：章节 / 教材批量操作

    /**
     * 管理端章节列表（含停用题，与 C 端口径不同）
     *
     * @return 章节列表，每行含教材名、教材顺序、章节名、章节顺序、总题数与启用题数；题库为空时返回空列表，不返回 null
     */
    List<QuizChapterAdminVO> listChaptersForAdmin();

    /**
     * 章节改名 / 调序（批量 UPDATE）
     *
     * @param request 改名请求，不能为 null；教材名、原章节名、新章节名必填；chapterSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该章题目数）；章节不存在时抛异常而不是返回 0
     * @throws BusinessException 章节不存在时抛 A0402
     */
    int renameChapter(QuizChapterRenameRequest request);

    /**
     * 整章移到另一本教材
     *
     * @param request 移动请求，不能为 null；来源教材名、章节名、目标教材名必填；toTextbookSeq 为 null 表示保持该章当前的教材顺序值不变
     * @return 受影响的题目行数（即该章题目数）
     * @throws BusinessException 来源章节不存在时抛 A0402
     */
    int moveChapter(QuizChapterMoveRequest request);

    /**
     * 整章删除（题目逻辑删除 + 同事务清理错题条目）
     *
     * @param request 删除请求，不能为 null；教材名与章节名必填
     * @return 实际删除的题目数；永不为 0（章节不存在会抛异常）
     * @throws BusinessException 章节不存在时抛 A0402
     */
    int deleteChapter(QuizChapterDeleteRequest request);

    /**
     * 教材改名 / 调序（批量 UPDATE）
     *
     * @param request 改名请求，不能为 null；原教材名与新教材名必填；textbookSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该教材的题目总数）
     * @throws BusinessException 教材不存在时抛 A0402
     */
    int renameTextbook(QuizTextbookRenameRequest request);
}

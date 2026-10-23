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
 * 刷题题库服务（管理端为主，另含 C 端的教材/章节树）
 *
 * 为什么 C 端的教材/章节树也在这里：它读的是同一张题目表、
 * 用的是同一条 GROUP BY。放到 PracticeService 里会让「谁负责读题库」
 * 这个边界变模糊——本类负责「题库的读与写」，PracticeService 负责
 * 「出题、判分、错题本」，职责按数据而不是按端划分。
 *
 * 教材与章节不是独立实体，因此这里没有 addChapter / deleteTextbook
 * 这类「新建一个空章节」的方法。章节随题目诞生，能做的只有
 * 「改名 / 调序 / 移章 / 整章删除」这四种批量操作。
 *
 * 写操作的三条共同纪律：
 * - 新增 / 修改 / 导入共用同一套跨字段校验（单选恰好 1 个正确项、
 * 多选至少 2 个、标签连续、正确项属于选项）；
 * - 修改是「部分更新」：字段为 null 表示不改，但校验时要用旧值补齐后再整体校验
 * （否则「只改解析」会因缺选项而报错）；
 * - 判定相关变更（改答案 / 改题型 / 增删选项）要重置该题所有用户错题条目的
 * 连对进度，但保留 status——已消解的不退回待复习。
 *
 * 约定：参数不合法 / 业务不允许时一律抛 BusinessException，
 * 不返回错误码、不返回 null。
 */
public interface QuizQuestionService extends IService<StudioQuizQuestion> {

    // ==================== C 端：教材 / 章节树 ====================

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * 只统计启用的题：用户看到的数字是「这一章有多少题可刷」，
     * 把停用题算进去会让用户点进去发现题数对不上。
     *
     * 一条 GROUP BY textbook_name, chapter_name 查完，再在内存里挂树——
     * 不写「先查教材、再逐个查章节」的 N+1。
     *
     * @return 教材列表（按 textbookSeq, name 升序），每本含其章节；
     * 题库为空时返回空列表，不返回 null
     */
    List<QuizTextbookVO> listCatalog();

    // ==================== 管理端：题目读写 ====================

    /**
     * 管理端分页查询题目（含选项与答案、作答数与正确率）
     *
     * 作答数与正确率由一次 GROUP BY question_id 填充，
     * 不是逐行 count（那是 N+1）。
     *
     * @param request 查询条件，允许整体为 null（表示无条件查第一页）
     * @return 分页结果
     */
    Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request);

    /**
     * 管理端按 id 查题目详情（含选项与答案）
     *
     * @param id 题目 ID
     * @return 详情
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题目不存在时抛出（A0402）
     */
    QuizQuestionAdminVO getQuestionById(long id);

    /**
     * 新增题目
     *
     * @param request 新增请求
     * @return 新题目 ID
     * @throws com.bhu.runshistudioweb.exception.BusinessException 字段不合法、
     * 跨字段规则不满足（如单选题 2 个正确项）、入库失败时抛出
     */
    long addQuestion(QuizQuestionAddRequest request);

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * 实现步骤固定为：取旧实体 → 用请求中非 null 字段覆盖 → 用旧值补齐后整体校验
     * → 检测判定相关变更 → 写库（必要时同事务重置错题连对进度）。
     *
     * @param request 修改请求（id 必填）
     * @return true 表示修改成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题目不存在、
     * 校验不通过、入库失败时抛出
     */
    boolean updateQuestion(QuizQuestionUpdateRequest request);

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * 作答记录保留：统计只读 result，不需要题目内容。
     * 错题条目必须清理——题目没了，条目无处可去，留着只会在错题本里
     * 渲染出一条「题目已不存在」的空行。
     *
     * @param id 题目 ID
     * @return 实际删除的题目数（1 或 0）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题目不存在时抛出
     */
    int deleteQuestion(long id);

    /**
     * 批量删除题目
     *
     * 语义是「把这些都删掉」，因此某条不存在时不中断，返回实际删除条数。
     * 但整个批次仍在一个事务里：要么全删、要么全不删，
     * 不会出现「删了一半，另一半因为某条异常而留在库里」。
     *
     * @param ids 题目 ID 列表
     * @return 实际删除条数
     */
    int batchDeleteQuestions(List<Long> ids);

    /**
     * 启用 / 停用题目
     *
     * 停用是可逆的下线：不再被抽到、不再出现在错题本里，
     * 但错题条目保留——重新启用后自动回来。它不清理任何数据。
     *
     * @param id 题目 ID
     * @param status 目标状态：0-启用，1-停用
     * @return true 表示成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 题目不存在、状态非法时抛出
     */
    boolean updateStatus(long id, int status);

    /**
     * 批量导入题目
     *
     * 整批全成功或全失败：任何一条解析或校验失败，整批都不落库。
     * 这是刻意的——「导入了一半」会让管理员无法判断哪些进了、哪些没进，
     * 只能全部删掉重来。
     *
     * @param request 导入请求（ dryRun=true 时只校验不落库）
     * @return 解析/导入结果，含逐行错误
     */
    QuizImportResultVO importQuestions(QuizQuestionImportRequest request);

    // ==================== 管理端：章节 / 教材批量操作 ====================

    /**
     * 管理端章节列表（含停用题，与 C 端口径不同）
     *
     * @return 章节列表（按教材、章节顺序升序）
     */
    List<QuizChapterAdminVO> listChaptersForAdmin();

    /**
     * 章节改名 / 调序（批量 UPDATE）
     *
     * 改名到已存在的章节时合并（两章并成一章），不报错——
     * 报错会让管理员不得不先把两章改成不同名字再改回来。
     *
     * @param request 改名请求
     * @return 受影响的题目行数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 章节不存在时抛出
     */
    int renameChapter(QuizChapterRenameRequest request);

    /**
     * 整章移到另一本教材
     *
     * 目标教材下已有同名章节时合并。移动后 textbook_seq 一并更新：
     * 章节顺序是「教材内」的概念，换了教材就得用新教材的顺序值。
     *
     * @param request 移动请求
     * @return 受影响的题目行数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 章节不存在时抛出
     */
    int moveChapter(QuizChapterMoveRequest request);

    /**
     * 整章删除（题目逻辑删除 + 同事务清理错题条目）
     *
     * 这是本模块唯一会批量删数据的接口，管理端必须二次确认并显示题数。
     *
     * @param request 删除请求
     * @return 实际删除的题目数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 章节不存在时抛出
     */
    int deleteChapter(QuizChapterDeleteRequest request);

    /**
     * 教材改名 / 调序（批量 UPDATE）
     *
     * @param request 改名请求
     * @return 受影响的题目行数
     * @throws com.bhu.runshistudioweb.exception.BusinessException 教材不存在时抛出
     */
    int renameTextbook(QuizTextbookRenameRequest request);
}

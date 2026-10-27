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
 * C 端只用到 listCatalog 一个方法，其余全部是管理端接口，由 Controller 上的
 * {@code @SaCheckRole("admin")} 把关——本接口不做角色校验，谁调用它谁负责把门，
 * 这一点与考勤、刷题两个模块（在实现里查成员表）不同。
 *
 * @author shaoshing
 */
public interface QuizQuestionService extends IService<StudioQuizQuestion> {

    // ==================== C 端：教材 / 章节树 ====================

    /**
     * 教材 → 章节树（C 端下拉数据源）
     *
     * 教材与章节是题目表上的两个名字列，因此「有哪些教材、每本有哪些章节」只能从题目表里聚合。
     * 章节的唯一键是「教材名 + 章节名」：不同教材可以有同名章节，只用章节名做键会把
     * 两本书的「第一章」合并成一个。
     *
     * 坑：只统计启用的题。用户看到的数字是「这一章有多少题可刷」，
     * 把停用题算进去会让他点进去发现题数对不上。排序值可能为 null，比较器必须处理 null。
     *
     * 本方法不做成员校验：C 端入口必须先调 QuizPracticeService#requireActiveMemberId，
     * 否则「只有账号、没有成员档案」的用户也能读到整个题库目录。
     *
     * @return 教材列表（按教材排序值、名称升序），每本含其章节（按章节排序值、名称升序）；
     *         题库为空时返回空列表，不返回 null
     */
    List<QuizTextbookVO> listCatalog();

    // ==================== 管理端：题目读写 ====================

    /**
     * 管理端分页查询题目（含选项与答案、作答数与正确率）
     *
     * 管理端要看的是「存量与质量」，因此带上正确答案与解析（供编辑回填），
     * 也带上每题的作答数与正确率。作答统计由一次 GROUP BY question_id 批量填充，
     * 不是逐行 count。
     *
     * 坑：正确率的分母为 0 时返回 null 而不是 0——0% 读起来像「全错」，那是错误的信息。
     * 关键词只模糊匹配题干，搜解析会让「解析里提过某个词」的题也混进来。
     *
     * @param request 查询条件，允许整体为 null（无条件查第一页）；
     *                字段为 null 表示该条件不参与筛选，不是「查 null 值」
     * @return 分页结果，记录里含选项、正确答案与解析；没有匹配数据时 records 为空列表（不是 null）
     * @throws BusinessException 题型或状态取值非法时抛 A0401
     */
    Page<QuizQuestionAdminVO> listQuestionByPage(QuizQuestionQueryRequest request);

    /**
     * 管理端按 id 查题目详情（含选项与答案）
     *
     * 编辑题目时需要回填完整内容，包括正确答案与解析，因此本接口会下发答案——
     * 它只对管理员开放，C 端走的是另一个结构上没有答案字段的 VO。
     *
     * 坑：本方法不做数据权限校验（比如「只能看自己录的题」），因为题库是公共资产，
     * 管理端角色已经是最小权限单位。若将来要支持多租户，必须在最前面加一层归属判断。
     *
     * @param id 题目 ID，必须大于 0（小于等于 0 抛 A0401）
     * @return 题目详情，含选项、正确答案与解析；永不为 null
     * @throws BusinessException id 非法（A0401）、题目不存在（含已逻辑删除，A0402）
     */
    QuizQuestionAdminVO getQuestionById(long id);

    /**
     * 新增题目
     *
     * 题型、选项、正确答案、解析四个字段互相牵制，单独校验任何一个都拦不住错误——
     * 「单选却有 2 个正确项」「正确答案指向不存在的选项」这类问题只能把字段放在一起看。
     *
     * 坑：新题一律以「启用」状态入库，DTO 里没有 status 字段。若允许新增时指定停用，
     * 管理员会得到一道「建好了但谁也刷不到」的题，而界面不会提示。
     *
     * @param request 新增请求，不能为 null；教材名、章节名、题干、题型、选项、正确答案必填；
     *                选项 2~6 个（多选至少 3 个），标签必须从 A 连续
     * @return 新题目 ID（雪花算法生成的 19 位 Long，接口层会序列化成字符串）
     * @throws BusinessException 字段不合法或跨字段规则不满足时抛 A0401；入库失败时抛 B0002
     */
    long addQuestion(QuizQuestionAddRequest request);

    /**
     * 修改题目（部分更新：字段为 null 表示不修改）
     *
     * 校验规则是跨字段的，只拿请求里传了的字段去校验，「只改解析」会因没传选项而误报。
     * 因此实现里固定为：取旧实体 → 用非 null 字段覆盖 → 用旧值补齐后整体校验 → 写库。
     * 检测「判定相关变更」必须在覆盖旧值之前算，否则比较时永远相等。
     *
     * 坑：刻意不判断更新行数。MySQL 默认协议下「SET 的值与原值完全相同」返回 0 行受影响，
     * 而「打开编辑框、什么都没改、点保存」是极常见的操作，当成失败会让用户莫名其妙。
     * 另外「只改选项文字」不算判定变更：标签集合没变，重置进度反而会误伤用户。
     *
     * @param request 修改请求，不能为 null；id 必填且为正数；其余字段为 null 表示不修改，
     *                解析与题源传空串表示清空
     * @return true 表示保存成功；不代表数据真的变了，什么都没改时也是 true
     * @throws BusinessException 题目不存在（A0402）、校验不通过（A0401）
     */
    boolean updateQuestion(QuizQuestionUpdateRequest request);

    /**
     * 删除题目（逻辑删除 + 同事务清理该题的全部错题条目）
     *
     * 题目没了，错题条目留着只会在错题本里渲染出空行；而作答记录必须保留——
     * 统计只读 result 列，删掉会让用户的累计作答数凭空减少。
     * 两次写同事务，顺序是先清条目、再删题目。
     *
     * 坑：返回 int 而不是 boolean，存在性已在前一步校验过，因此返回 0 只可能是并发删除。
     * 这是逻辑删除，数据没有从库里消失，不要在业务代码里改成物理删除。
     *
     * @param id 题目 ID，必须大于 0
     * @return 实际删除的题目数（1 或 0）
     * @throws BusinessException id 非法（A0401）或题目不存在（A0402）
     */
    int deleteQuestion(long id);

    /**
     * 批量删除题目
     *
     * 语义是「把这些都删掉」：某条不存在时不中断，返回实际删除条数——
     * 批量操作里「其中一条已被别人删掉」是常态。但整个批次仍在一个事务里，
     * 不会出现「删了一半」。
     *
     * 坑：返回值是「实际删掉的条数」，不等于入参长度，也不等于去重后的长度，
     * 前端若用它显示「已删除 N 题」需要接受 N 可能小于勾选数。
     *
     * @param ids 题目 ID 列表，不能为 null 或空（否则抛 A0401）；列表里可以含 null 与非正数，
     *            它们会被静默跳过
     * @return 实际删除条数，可能为 0（全部都不存在时）
     * @throws BusinessException 列表为空时抛 A0401
     */
    int batchDeleteQuestions(List<Long> ids);

    /**
     * 启用 / 停用题目
     *
     * 停用是可逆的下线：不再被抽到、不再出现在错题本里，但错题条目保留，重新启用后自动回来。
     * 它不清理任何数据，因此不需要事务，也不该顺手去改错题本。
     *
     * 坑：状态取值是 0-启用、1-停用，与「true 表示启用」的直觉相反，
     * 传反了会把整章题下线而界面不会有任何异常。另外已发给用户的批次里的停用题仍然可以提交作答。
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
     * 整批全成功或全失败：任何一条解析或校验失败，整批都不落库。「导入了一半」会让管理员
     * 无法判断哪些进了、哪些没进，只能全部删掉重来。代价是一次导入的错误要一次性看完，
     * 因此返回值里带逐行错误与行号。
     *
     * 坑：dryRun 时 imported 恒为 0，判断「这批能不能导」要看 errors 是否为空。
     * 另外本方法带事务，整批 insert 在同一个事务里，一次导入几千题会长时间持有事务。
     *
     * @param request 导入请求，不能为 null；教材名、章节名、待解析文本必填；
     *                dryRun 为 null 时按 false（真导入）处理；startSeq 为 null 时按 0 处理
     * @return 导入结果，含解析成功数、实际入库数、是否 dryRun 与逐行错误列表；
     *         有错误时 imported 为 0 且整批未落库；永不为 null
     * @throws BusinessException 请求体为 null（A0401）、入库失败（B0002）。
     *         单行内容不合法不会抛异常，而是以错误明细的形式出现在返回值里
     */
    QuizImportResultVO importQuestions(QuizQuestionImportRequest request);

    // ==================== 管理端：章节 / 教材批量操作 ====================

    /**
     * 管理端章节列表（含停用题，与 C 端口径不同）
     *
     * C 端只数启用题（用户看到的是「能刷多少」），管理端要数全部题（「这一章有多少存量」），
     * 因此单独给一个 VO 并额外返回「其中启用了几题」。两端共用同一个 VO，
     * 必然有一端拿到的数字是错的，而这种错不会报错。
     *
     * 这是扁平的章节列表而不是嵌套的树：每一行都带教材名，前端要按教材分组得自己聚合。
     *
     * @return 章节列表，每行含教材名、教材顺序、章节名、章节顺序、总题数与启用题数；
     *         题库为空时返回空列表，不返回 null
     */
    List<QuizChapterAdminVO> listChaptersForAdmin();

    /**
     * 章节改名 / 调序（批量 UPDATE）
     *
     * 章节没有独立表，改名就是一条批量 UPDATE。逐条更新既慢，又会中途失败留下半章。
     * 改名到已存在的章节时合并（两章并成一章）而不报错——报错会让管理员不得不先把两章
     * 改成不同名字再改回来，而他想的大概率就是合并。
     *
     * 坑：新旧名字相同时这条 UPDATE 变成「只调序」，因此「改名为自己」是合法操作而不是空操作。
     * 返回值是受影响的题目行数而不是章节数，一章可能有几百道题。
     *
     * @param request 改名请求，不能为 null；教材名、原章节名、新章节名必填；
     *                chapterSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该章题目数）；章节不存在时抛异常而不是返回 0
     * @throws BusinessException 章节不存在时抛 A0402
     */
    int renameChapter(QuizChapterRenameRequest request);

    /**
     * 整章移到另一本教材
     *
     * 目标教材下已有同名章节时合并，不报错。移动后教材顺序值要一并更新：
     * 章节顺序是「教材内」的概念，换了教材就该用新教材的顺序值，否则排序会错乱。
     *
     * 坑：本方法不校验目标教材是否存在。教材是题目上的名字列而不是实体，
     * 「移到一本还不存在的教材」在数据上完全合法，实际效果是新建了那本教材。
     * 不传 toTextbookSeq 时会沿用原值，章节在新教材里的位置可能落到意料之外的地方。
     *
     * @param request 移动请求，不能为 null；来源教材名、章节名、目标教材名必填；
     *                toTextbookSeq 为 null 表示保持该章当前的教材顺序值不变
     * @return 受影响的题目行数（即该章题目数）
     * @throws BusinessException 来源章节不存在时抛 A0402
     */
    int moveChapter(QuizChapterMoveRequest request);

    /**
     * 整章删除（题目逻辑删除 + 同事务清理错题条目）
     *
     * 这是本模块唯一会批量删数据的接口，因此管理端必须二次确认并在确认框里显示题数。
     * 作答记录保留：统计只读 result，不需要题目内容。
     *
     * 坑：按「教材名 + 章节名」定位而不是按章节 ID——教材章节没有 ID，
     * 若两个章节被改名合并成一个，这里会把合并后的整章一起删掉。
     *
     * @param request 删除请求，不能为 null；教材名与章节名必填
     * @return 实际删除的题目数；永不为 0（章节不存在会抛异常）
     * @throws BusinessException 章节不存在时抛 A0402
     */
    int deleteChapter(QuizChapterDeleteRequest request);

    /**
     * 教材改名 / 调序（批量 UPDATE）
     *
     * 教材同样没有独立表，它就是题目上的 textbook_name + textbook_seq 两列，
     * 因此改名就是一条批量 UPDATE，改到已存在的名字时自然合并。教材改名会把它下面
     * 所有章节一起带走。
     *
     * 坑：这是全库范围的批量更新，改错一个名字会影响整本教材的所有题目，
     * 而接口不会提示「影响了多少章节」。前端确认框里应当先展示「该教材下有 N 章 M 题」。
     *
     * @param request 改名请求，不能为 null；原教材名与新教材名必填；
     *                textbookSeq 为 null 表示不修改顺序
     * @return 受影响的题目行数（即该教材的题目总数）
     * @throws BusinessException 教材不存在时抛 A0402
     */
    int renameTextbook(QuizTextbookRenameRequest request);
}

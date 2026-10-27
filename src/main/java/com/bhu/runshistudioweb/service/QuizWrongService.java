package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.dto.quiz.QuizWrongQueryRequest;
import com.bhu.runshistudioweb.model.entity.StudioQuizWrong;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongDetailVO;
import com.bhu.runshistudioweb.model.vo.quiz.QuizWrongItemVO;

/**
 * 刷题错题本服务
 *
 * 状态机的唯一写入口是 applyOutcome，只被 QuizPracticeServiceImpl 在判分之后调用：
 * 答错入本或重置为待复习，答对累加连对、达阈值才消解，跳过不调用本方法。
 * 多入口各写一套规则，迟早出现「答题页说已消解、错题本页还显示待复习」的不一致。
 *
 * 题目被停用 / 删除时本表不动：过滤发生在查询侧，停用因此可逆——
 * 若改成「停用时删条目」，重新启用后用户的错题记录就永久消失了。
 *
 * 本接口不做成员校验，准入由调用方负责。
 *
 * @author shaoshing
 */
public interface QuizWrongService extends IService<StudioQuizWrong> {

    /**
     * 按一次作答结果更新错题本（状态机的唯一写入口）
     *
     * 答错加入待复习、在错题本里答对累加连对、连对达到阈值才消解、已消解的再答错回到待复习。
     * 先查一次当前条目，再按对错分两条支路，两支都只改这一条记录。
     * 答对时绝不为「从没做错过的题」建条目，否则「已消解」的含义会变成「做对过的题数」。
     *
     * 坑：本方法带事务，但做的是「读一次 + 写一次」，没有行锁保护。同一用户对同一题并发提交时，
     * 两个线程可能都查到「条目不存在」然后都去 insert，撞唯一键的那个抛异常回滚。
     * 真正拦住重复提交的是作答表上的唯一键——错题本的写入总是跟在「作答记录插入成功」之后。
     *
     * @param userId 用户 ID，准入由调用方校验，本方法不查成员表
     * @param questionId 题目 ID，必须是调用方刚刚判过分的题
     * @param correct 本次是否答对；答错会累加错误次数并把连对清零，已消解的条目会被打回待复习
     * @param resolveStreak 消解阈值（连续答对几次算掌握），小于 1 时按 1 处理
     * @return 本次对错题本的影响，取值见 QuizVerdictVO#wrongBookAction
     *         （added / reopened / progress / resolved / none）；永不为 null
     */
    Outcome applyOutcome(long userId, long questionId, boolean correct, int resolveStreak);

    /**
     * 我的错题本分页
     *
     * 列表与计数必须用同一套过滤条件，否则会出现「概览显示 9 条待复习、点进去只有 7 条」
     * 这种自相矛盾的界面，而它不会报错，只会让用户觉得数据不可信。
     * 按「最久没复习」升序——错题本的价值在于把忘掉的捡回来，而不是反复刷最近刚错的那道。
     *
     * 坑：列表不含答案。浏览列表时并没有复盘意图，在列表里泄露答案就是作弊捷径。
     * total 是过滤后的总数，极端情况下 records 可能少于 total，那不是 bug。
     *
     * @param userId 用户 ID，必须是当前登录用户；本方法不校验归属，调用方负责传自己的 ID
     * @param request 查询条件（教材名 / 章节名 / 分页参数），允许为 null
     * @return 分页结果，记录不含答案；没有待复习条目时返回空页（records 为空列表、total 为 0）
     */
    Page<QuizWrongItemVO> listMyWrongByPage(long userId, QuizWrongQueryRequest request);

    /**
     * 错题详情（含答案与解析）
     *
     * 能进错题本的前提是「这道题你做错过」，藏着答案反而让他无法复盘；列表则不带答案。
     * 「不存在」与「不属于本人」返回同一个错误：分开报错就能靠错误码枚举出哪些 ID 真实存在。
     *
     * 坑：正确答案取的是题目表里的当前值，不是作答记录里的快照。管理员改过答案后，
     * 复盘时两者可能对不上——这是刻意的取舍：详情页要回答「正确答案是什么」。
     *
     * @param userId 当前登录用户 ID，用于归属校验；不是本人的条目一律按「不存在」处理
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛 A0401
     * @return 错题详情，含选项、正确答案、解析与上次所选；lastChosenLabels 没有答错记录时为 null
     * @throws BusinessException id 非法（A0401）、条目不存在或不属于当前用户（A0301）、
     *         题目已被删除（A0402）
     */
    QuizWrongDetailVO getMyWrongDetail(long userId, long id);

    /**
     * 手动「标记已掌握」
     *
     * 自动消解要求连续答对若干次，但用户可能已经通过别的材料掌握了这道题。
     * 手动入口跳过连对计数，直接把状态置为已消解。
     *
     * 坑：已消解时直接返回 true 且不改 resolvedAt——重复点击不该把「什么时候掌握的」
     * 这个时间点改掉，否则错题本的时间线就失去了意义。
     * 本方法没有事务注解：它只写一行、没有跨表联动，但将来若顺带清理别的东西就必须补上。
     *
     * @param userId 当前登录用户 ID，用于归属校验
     * @param id 错题本条目 ID（不是题目 ID）；小于等于 0 时抛 A0401
     * @return true 表示成功，包含「本来就已经消解」的情况；false 只可能是并发删除
     * @throws BusinessException id 非法（A0401）、条目不存在或不属于当前用户（A0301）
     */
    boolean resolveManually(long userId, long id);

    /**
     * 待复习错题数（学习概览用）
     *
     * 这个数字必须与错题本列表的条数完全一致，否则用户会看到「概览说 9 条、点进去只有 7 条」。
     * 因此过滤条件与 listMyWrongByPage 完全相同，连「题目可见」的子查询都是同一份定义。
     *
     * 坑：统计的是条目数而不是题目数（「用户 + 题目」唯一键保证两者相等）。
     * 待复习数与已消解数相加不一定等于总条目数，因为停用题目的条目两边都不计。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 待复习条目数；没有错题时返回 0（不是 null）
     */
    int countPending(long userId);

    /**
     * 已消解错题数（学习概览用）
     *
     * 口径是「当前状态」而不是「历史累计」：已消解的题再被答错会回到待复习，这个数字就该减 1。
     * 按历史累计算会只增不减，对用户没有行动指引。
     *
     * 坑：本方法与 countPending 必须用同一套可见性口径，否则两个数字加起来会对不上。
     * 停用的题不显示，但条目本身保留，重新启用后会自动回到这个数字里。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @return 已消解条目数（按当前状态统计）；没有时返回 0（不是 null）
     */
    int countResolved(long userId);

    /**
     * 取待复习的题目 ID（错题重刷的候选池，按「最久没复习」排序）
     *
     * 错题重刷的候选池必须与顺序模式严格区分开：顺序模式排除已作答的题，
     * 错题模式只出待复习的题，两个集合不重叠，用户才不会在两个入口看到同一批题。
     *
     * 坑：范围过滤用的是列表的 contains，范围题量大时是 O(n²)。这里没有改成集合，
     * 是因为一次错题重刷的候选量由题量配置封顶，而「保留原顺序」比这点开销重要得多。
     * 另外传空串与传 null 不等价：本方法只把 null 当作「不限」，调用方必须先做 trim 与空值归一。
     *
     * @param userId 用户 ID；调用方必须传当前登录用户
     * @param textbookName 教材名称，为 null 表示不限
     * @param chapterName 章节名称，为 null 表示不限；只传章节不传教材无法定位，调用方需先校验
     * @return 题目 ID 列表，按「最久没复习」升序；没有待复习题时返回空列表（不是 null）
     */
    java.util.List<Long> listPendingQuestionIds(long userId, String textbookName, String chapterName);

    /**
     * 本次作答对错题本的影响
     *
     * 调用方需要同时拿到「发生了什么」与「还差几次消解」：前者决定提示文案，
     * 后者决定「再答对 N 次即掌握」的进度提示。record 天然不可变，避免调用方拿到结果后又改掉。
     *
     * 坑：remainingStreak 只在 progress 时有意义，但 resolved 时实现里给的是 0 而不是 null。
     * 因此前端判断「要不要显示进度提示」必须看 action，否则刚消解时会显示「再答对 0 次」。
     *
     * @param action 影响类型，对应 QuizVerdictVO#wrongBookAction：added / reopened / progress /
     *               resolved / none；永不为 null
     * @param remainingStreak 还差几次消解，仅 action 为 progress 时有意义；其余情况为 null
     *                        （resolved 时为 0），前端需按 action 判断
     */
    record Outcome(String action, Integer remainingStreak) {
    }
}

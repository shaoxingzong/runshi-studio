package com.bhu.runshistudioweb.model.enums;

/**
 * 错题本状态（studio_quiz_wrong.status）
 *
 * 取值必须与 DDL 中 status 的列注释保持一致：0-待复习, 1-已消解。
 *
 * 「已消解」不是「永久移除」：它只表示「连续答对达到阈值」或「用户手动标记掌握」。
 * 同一道题再答错时，条目会自动回到待复习（ resolved_at 清空），
 * 不需要任何「重开」代码——这是把状态机放在一张表里换来的简洁。
 *
 * @author shaoshing
 */
public enum QuizWrongStatusEnum {

    /** 待复习：出现在错题本列表与「错题重刷」的候选池里 */
    PENDING(0, "待复习"),

    /** 已消解：不再出现在待复习列表，但条目保留（再答错会回到待复习） */
    RESOLVED(1, "已消解");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与前端标签 */
    private final String desc;

    QuizWrongStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与前端标签 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的数字翻译成错题本状态，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为两条路径的要求相反。
     * 前端按状态筛选错题（只看待复习）时，传一个 5 进来必须报参数错误——
     * 否则会正常返回空列表，用户会以为「我的错题本空了」，这是会让人慌的误判。
     * 而展示单条错题时，历史脏值只该让这一格显示成「未知」，不该让整页报错。
     * 把处置权交给知道上下文的调用方。
     *
     * <p>为什么不兜底成 PENDING：兜底会把「已消解的题」显示成「待复习」，
     * 用户会反复看到自己已经掌握的题，而这正是错题本最该避免的体验；
     * 同时数据问题也被彻底藏住了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：GET 参数不传、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code status.value == value} 能正确比较，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱；若字段改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有缓存范围内的值碰巧相等。
     *
     * @param value 待翻译的取值，允许为 null；合法取值为 0 / 1
     * @return 命中的枚举常量；传 null 或不是 0 / 1 时返回 null，调用方必须判空
     */
    public static QuizWrongStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (QuizWrongStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // ③ 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }
}

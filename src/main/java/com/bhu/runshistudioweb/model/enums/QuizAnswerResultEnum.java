package com.bhu.runshistudioweb.model.enums;

/**
 * 作答结果（studio_quiz_answer.result）
 *
 * 取值必须与 DDL 中 result 的列注释保持一致：1-正确, 2-错误, 3-跳过。
 *
 * SKIPPED 是事件而不是作答，这个区分贯穿整个模块：
 * - 它落库：否则断点续答无法区分「处理过」与「还没轮到」，
 * 且幂等键（同一批同一题只判一次）也没法工作；
 * - 它不写错题本：这是「跳过不记错」的唯一实现点；
 * - 它不计入正确率：跳过不是错误，把分母算进去会让正确率虚低；
 * - 它的响应不含答案：否则跳过按钮就成了看答案按钮，顺序模式会被刷穿。
 *
 * @author shaoshing
 */
public enum QuizAnswerResultEnum {

    /** 正确：所选与正确项集合完全相等 */
    CORRECT(1, "正确"),

    /** 错误：漏选、多选、错选均归此类（多选不给部分分） */
    WRONG(2, "错误"),

    /** 跳过：用户主动跳过，不记错、不给答案、不计入正确率 */
    SKIPPED(3, "跳过");

    /** 入库的值（与 DDL 中 result 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizAnswerResultEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 result 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库里的作答结果翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：这个枚举用在统计与回显两条路径上，两者对脏值的容忍度不同。
     * 统计正确率时，认不出的结果应当被明确排除并记一笔日志（否则会被算成「错误」，
     * 让正确率凭空变低）；而回显历史作答时，一条脏数据只该让这一行显示成「未知」，
     * 不能让整页作答记录打不开。查不到返回 null，把处置权交给调用方，
     * 比在这里替它选一种行为安全。
     *
     * <p>为什么不兜底成 WRONG：兜底会把「数据坏了」伪装成「用户答错了」，
     * 正确率被悄悄拉低，而没有人会去查——这是最难发现的一类偏差。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：旧数据可能没有这一列，或不传参，null 必须原样返回；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code result.value == value} 能正确比较，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱。若把字段改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有缓存范围内的值碰巧相等。
     *
     * @param value 数据库中的结果值，允许为 null；合法取值为 1 / 2 / 3
     * @return 命中的枚举常量；传 null 或不是 1 / 2 / 3 时返回 null，调用方必须判空
     */
    public static QuizAnswerResultEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增结果类型时这里不用改
        for (QuizAnswerResultEnum result : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (result.value == value) {
                return result;
            }
        }
        // ③ 查不到返回 null：统计路径据此排除脏值，而不是把它算成答错
        return null;
    }
}

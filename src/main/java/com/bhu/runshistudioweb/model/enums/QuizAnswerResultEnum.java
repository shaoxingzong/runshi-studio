package com.bhu.runshistudioweb.model.enums;

/**
 * 作答结果（studio_quiz_answer.result）
 *
 * 取值与 DDL 列注释同源：1-正确，2-错误，3-跳过。
 *
 * 坑：SKIPPED 是事件而不是作答——它必须落库（断点续答与幂等要靠它），
 * 但不写错题本、不计入正确率、响应不含答案，否则跳过按钮就成了看答案按钮。
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
     * 把作答结果翻译成枚举，查不到返回 null，不兜成 WRONG
     *
     * 兜底会把「数据坏了」伪装成「用户答错了」，正确率被悄悄拉低且无人去查；
     * 统计路径据此排除脏值。null 必须先挡，否则拆箱比较会 NPE。
     *
     * @param value 数据库中的结果值，允许为 null
     * @return 命中的枚举；null 或不是 1 / 2 / 3 时返回 null
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

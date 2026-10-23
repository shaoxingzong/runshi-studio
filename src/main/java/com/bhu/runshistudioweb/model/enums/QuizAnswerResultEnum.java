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
     * 按取值查找结果
     *
     * @param value 数据库中的结果值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static QuizAnswerResultEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (QuizAnswerResultEnum result : values()) {
            if (result.value == value) {
                return result;
            }
        }
        return null;
    }
}

package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题题型（<b>闭集，只有选择题</b>）
 *
 * author: shaoshing
 *
 * <p><b>为什么没有「判断」</b>：判断题等价于一道选项为「正确 / 错误」的单选，
 * 用 {@link #SINGLE} 就能表达。单独设一个类型只会多一条判分分支、
 * 一套交互与一个导入标记，而表达能力没有任何提升。
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code studio_quiz_question.type}
 * 的列注释保持一致：{@code 0-单选, 1-多选}。
 *
 * <p><b>两种题型的约束不同，且都由应用层校验</b>（不写进 DDL）：
 * <ul>
 *     <li>单选：恰好 <b>1</b> 个正确项，提交时恰好 1 个标签；</li>
 *     <li>多选：至少 <b>2</b> 个正确项，提交时至少 1 个标签，<b>全对才得分</b>（不给部分分）。</li>
 * </ul>
 * 这些规则将来可能放宽（例如允许「2 选 1」的多选变体），写进 DDL 就得改表。
 */
public enum QuizQuestionTypeEnum {

    /** 单选：恰好 1 个正确项 */
    SINGLE(0, "单选"),

    /** 多选：至少 2 个正确项，漏选/多选/错选均判错 */
    MULTIPLE(1, "多选");

    /** 入库的值（与 DDL 中 type 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizQuestionTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 type 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找题型
     *
     * @param value 数据库中的题型值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static QuizQuestionTypeEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (QuizQuestionTypeEnum type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code 0（单选） / 1（多选）}
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizQuestionTypeEnum type : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

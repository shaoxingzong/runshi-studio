package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题题型（studio_quiz_question.type，闭集只有选择题）
 *
 * 没有「判断」：判断题等价于选项为「正确 / 错误」的单选，单设一类只会多一条判分分支。
 * 取值与 DDL 列注释同源：0-单选，1-多选。
 *
 * 题型约束由应用层校验：单选恰好 1 个正确项；多选至少 2 个，全对才得分（不给部分分）。
 *
 * @author shaoshing
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
     * 把题型数字翻译成枚举，查不到返回 null
     *
     * 题型决定判分规则，导入与新增必须判空报错，否则一个 2 入库后每次作答都判分异常；
     * 回显历史题目时可降级成「未知题型」。
     *
     * @param value 待翻译的题型值，允许为 null
     * @return 命中的枚举；null 或不是 0 / 1 时返回 null
     */
    public static QuizQuestionTypeEnum of(Integer value) {
        // ① 先挡 null：导入文件的题型列可能为空，null 是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增题型时这里不用改
        for (QuizQuestionTypeEnum type : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (type.value == value) {
                return type;
            }
        }
        // ③ 查不到返回 null：写路径据此拒绝导入，而不是猜一个题型
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法题型文案，如「0（单选）/ 1（多选）」
     *
     * 数字本身没有语义，必须带中文描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizQuestionTypeEnum type : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字本身没有语义
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

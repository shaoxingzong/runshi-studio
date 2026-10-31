package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题模式（不落库，对应前端的三张模式卡）
 *
 * 三种模式的候选池必须不重叠，否则用户会在两个入口刷到同一批题。
 *
 * @author shaoshing
 */
public enum QuizModeEnum {

    /** 顺序刷题：按教材章节逐题，排除已作答的 */
    SEQUENTIAL(0, "顺序刷题"),

    /** 错题重刷：只刷错题本里待复习的题 */
    WRONG(1, "错题重刷"),

    /** 随机刷题：范围内随机抽题 */
    RANDOM(2, "随机刷题");

    /** 与前端约定的取值（前端 QuizMode 类型） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizModeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 与前端约定的取值 */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把前端传来的模式值翻译成枚举，查不到返回 null
     *
     * @param value 前端传来的模式值，允许为 null
     * @return 命中的枚举；null 或不是 0 / 1 / 2 时返回 null
     */
    public static QuizModeEnum of(Integer value) {
        // 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // 遍历全部常量：按声明的 value 比对，不用 ordinal
        for (QuizModeEnum mode : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (mode.value == value) {
                return mode;
            }
        }
        // 查不到返回 null：开批接口据此报「模式取值非法」
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法模式文案，如「0（顺序刷题）/ 1（错题重刷）/ 2（随机刷题）」
     *
     * 数字本身没有语义，必须带中文描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizModeEnum mode : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：数字本身没有语义
            text.append(mode.value).append("（").append(mode.desc).append("）");
        }
        return text.toString();
    }
}

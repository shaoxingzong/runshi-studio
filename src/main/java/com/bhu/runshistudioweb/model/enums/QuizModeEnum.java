package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题模式（对应前端的三张模式卡）
 *
 * 它不落库：批次只在 studio_quiz_answer.batch_id 上留下痕迹，
 * 模式本身由前端持有（题目不含答案，整批交给前端即可）。
 * 因此本枚举只服务于「开批时决定选题策略」，没有对应的 DDL 列。
 *
 * 三种模式的职责必须不重叠，否则用户会在两个入口看到同一批题：
 * - SEQUENTIAL：从本章第一题往后走，跳过已作答（对或错）的题。
 * 答错的题不会在这里反复出现——它们归错题重刷管；
 * - WRONG：只出待复习的错题，最久没复习的先来；
 * - RANDOM：范围内全部启用题里随机抽，一轮内不重复。
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
     * 按取值查找模式
     *
     * @param value 前端传来的模式值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static QuizModeEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (QuizModeEnum mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 0（顺序刷题）/ 1（错题重刷）/ 2（随机刷题）
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizModeEnum mode : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(mode.value).append("（").append(mode.desc).append("）");
        }
        return text.toString();
    }
}

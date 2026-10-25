package com.bhu.runshistudioweb.model.enums;

/**
 * 错题消解方式（studio_quiz_wrong.resolve_type）
 *
 * 取值必须与 DDL 中 resolve_type 的列注释保持一致：
 * 1-连对达标（自动）, 2-手动标记。
 *
 * 为什么要记录「怎么消解的」：两种消解的可信度不同——
 * 自动消解意味着用户确实答对了，手动标记只是用户「觉得自己会了」。
 * 将来若要做「掌握度」评估或错题推荐，这个字段就是区分依据；
 * 而它只占一个 tinyint，不留会永远拿不回来。
 *
 * @author shaoshing
 */
public enum QuizResolveTypeEnum {

    /** 自动：连续答对达到 studio.quiz.resolve-streak 次 */
    AUTO(1, "连对达标"),

    /** 手动：用户点「标记已掌握」 */
    MANUAL(2, "手动标记");

    /** 入库的值（与 DDL 中 resolve_type 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizResolveTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 resolve_type 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找消解方式
     *
     * @param value 数据库中的 resolve_type 值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static QuizResolveTypeEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (QuizResolveTypeEnum type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}

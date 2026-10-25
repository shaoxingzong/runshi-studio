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
     * 按取值查找枚举
     *
     * @param value 数据库 / 前端传来的取值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static QuizWrongStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (QuizWrongStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }
}

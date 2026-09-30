package com.bhu.runshistudioweb.model.enums;

/**
 * 项目状态枚举（studio_project.status，tinyint）
 *
 * author: shaoshing
 *
 * <p>为什么用 tinyint 而不是 varchar（DESIGN.md 4.2 的判定标准）：
 * 它是<b>纯状态位</b>——取值固定为三个、几乎不会扩展，且官网要按它做数值筛选
 * （「只看已上线的项目」{@code ?status=1}）。数字存 + 枚举约束，既省空间也便于精确匹配。
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code status} 的列注释保持一致：
 * {@code 0-研发中, 1-已上线, 2-已结题}，且 DDL 的默认值是 {@code 1}。
 * 因此「新增项目不传 status」时的兜底也必须取 {@link #ONLINE}，不能随便挑一个——
 * 否则同一份代码会出现「DB 默认 1、Service 默认 0」两套口径。
 *
 * <p>与 {@link TeamPositionEnum} 一样：没有框架注解引用它的字面量，故不配套常量类。
 */
public enum ProjectStatusEnum {

    /** 研发中：还没上线，官网一般只在后台可见 */
    DEVELOPING(0, "研发中"),

    /** 已上线：官网默认展示的群体（DDL 默认值 1） */
    ONLINE(1, "已上线"),

    /** 已结题：历史项目，保留展示但不再维护 */
    ARCHIVED(2, "已结题");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ProjectStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找状态
     *
     * @param value 数据库中的状态值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static ProjectStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (ProjectStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code 0（研发中） / 1（已上线） / 2（已结题）}
     *
     * @return 以「 / 」分隔的合法取值（含中文含义，因为数字单独看没有语义）
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ProjectStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.model.enums;

/**
 * 成员状态枚举（studio_member.member_status）
 *
 * author: shaoshing
 *
 * <p>为什么用 tinyint 而不是 varchar（DESIGN.md 4.2 的判定标准）：
 * 它是纯状态位，取值只有「在读/在队」与「毕业/离队」两种、极少扩展，
 * 且官网需要按它做数值筛选（免费查询新增接口 {@code ?memberStatus=0}）。
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code member_status} 的列注释保持一致：
 * {@code 0-在读/在队, 1-毕业/离队}。
 *
 * <p>与 {@link UserRoleEnum} 的差别同 {@link TeamPositionEnum}：
 * 没有框架注解引用它的字面量，故不配套常量类。
 */
public enum MemberStatusEnum {

    /** 在读/在队：官网成员页默认展示的群体 */
    IN_TEAM(0, "在读/在队"),

    /** 毕业/离队：官网「已毕业成员」板块使用 */
    GRADUATED(1, "毕业/离队");

    /** 入库的值（与 DDL 中 member_status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    MemberStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 member_status 列取值一致） */
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
    public static MemberStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (MemberStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code 0（在读/在队） / 1（毕业/离队）}
     *
     * @return 以「 / 」分隔的合法取值（含中文含义，因为数字单独看没有语义）
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (MemberStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}
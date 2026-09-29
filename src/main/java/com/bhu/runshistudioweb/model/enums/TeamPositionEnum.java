package com.bhu.runshistudioweb.model.enums;

/**
 * 团队职务枚举（studio_member.team_position）
 *
 * author: shaoshing
 *
 * <p>为什么用 varchar 而不是 tinyint（DESIGN.md 4.2 的判定标准）：
 * 职务取值会扩展（以后可能加「队长 / 组长 / 组员」之外的「顾问」等），
 * 且需要直接参与接口返回给前端渲染标签；字符串值排障时一眼读懂。
 *
 * <p>与 {@link UserRoleEnum} 的差别：team_position 没有任何框架注解需要引用它的字面量
 * （不像 user_role 要被 {@code @SaCheckRole} 当编译期常量引用），
 * 因此**不需要**配套的常量类，枚举自身就是唯一取值来源。
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code team_position} 的列注释保持一致：
 * {@code member-成员, leader-队长, tech_lead-组长}；
 * 入库前统一用 {@link #of(String)} 校验，禁止魔法字符串。
 */
public enum TeamPositionEnum {

    /** 成员：工作室普通成员 */
    MEMBER("member", "成员"),

    /** 队长：工作室负责人 */
    LEADER("leader", "队长"),

    /** 组长：技术方向负责人 */
    TECH_LEAD("tech_lead", "组长");

    /** 入库的值（与 DDL 中 team_position 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    TeamPositionEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 team_position 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找职务（大小写敏感）
     *
     * @param value 数据库中的职务值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static TeamPositionEnum of(String value) {
        for (TeamPositionEnum position : values()) {
            if (position.value.equals(value)) {
                return position;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code member / leader / tech_lead}
     *
     * <p>用它拼错误提示，避免「提示说支持 A、B，实际只认 A」的文案误导问题
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (TeamPositionEnum position : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(position.value);
        }
        return text.toString();
    }
}
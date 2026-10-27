package com.bhu.runshistudioweb.model.enums;

/**
 * 团队职务（studio_member.team_position）
 *
 * 用 varchar 而非 tinyint：取值会扩展，且要直接参与接口返回给前端渲染标签。
 * 取值与 DDL 列注释同源：member / leader / tech_lead；入库前统一用 of 校验，禁止魔法字符串。
 *
 * 没有注解引用其字面量，故不配套常量类（@SaCheckRole 那种才需要）。
 *
 * @author shaoshing
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
     * 把职务字符串翻译成枚举，查不到返回 null，不兜成 MEMBER
     *
     * 兜底会把「档案写错了」悄悄改成普通成员，队长或组长的展示静默降级；
     * 比较区分大小写且下划线不能省，常量在前调用 equals 天然免判空。
     *
     * @param value 待翻译的职务值，允许为 null
     * @return 命中的枚举；null 或不在三个合法取值内时返回 null
     */
    public static TeamPositionEnum of(String value) {
        // ① 遍历全部常量：新增职务时这里不用改
        for (TeamPositionEnum position : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (position.value.equals(value)) {
                return position;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「member / leader / tech_lead」
     *
     * 职务值是英文单词，只拼取值即可读；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (TeamPositionEnum position : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 只拼取值：职务值是英文单词，本身已经可读
            text.append(position.value);
        }
        return text.toString();
    }
}
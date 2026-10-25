package com.bhu.runshistudioweb.model.enums;

/**
 * 团队职务枚举（studio_member.team_position）
 *
 * 为什么用 varchar 而不是 tinyint：
 * 职务取值会扩展（以后可能加「队长 / 组长 / 组员」之外的「顾问」等），
 * 且需要直接参与接口返回给前端渲染标签；字符串值排障时一眼读懂。
 *
 * 与 UserRoleEnum 的差别：team_position 没有任何框架注解需要引用它的字面量
 * （不像 user_role 要被 @SaCheckRole 当编译期常量引用），
 * 因此不需要配套的常量类，枚举自身就是唯一取值来源。
 *
 * 取值必须与 db/user.sql 中 team_position 的列注释保持一致：
 * member-成员, leader-队长, tech_lead-组长；
 * 入库前统一用 of 校验，禁止魔法字符串。
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
     * 把数据库或前端传来的职务字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为写路径与读路径
     * 的正确处置相反。管理员维护成员档案时，职务写错必须报参数错误，
     * 否则官网会渲染出一个没有样式的标签（前端只认识三个值）；
     * 而展示历史档案时，若库里存在早期写法或手工改库留下的脏值，
     * 只该让这一格显示成「未知职务」，不能让整页成员列表 500。
     *
     * <p>为什么不兜底成 MEMBER：兜底等于把「档案写错了」悄悄改成「普通成员」，
     * 队长或组长的权限展示会静默降级，而本人和管理员都不会收到任何提示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较——常量在前，
     *     入参为 null 时返回 false 而不是抛 NPE；</li>
     *     <li>命中即返回，走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，且下划线不能省——"techLead"、"techlead"
     * 都查不到。这类拼写错误不会抛异常，只会静默返回 null，
     * 因此写路径必须拿返回值判空后再入库，不能直接信任前端传的字符串。
     *
     * @param value 待翻译的职务值，允许为 null；合法取值为 member / leader / tech_lead
     * @return 命中的枚举常量；传 null 或不是三个合法取值之一时返回 null，调用方必须判空
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
     * 把所有合法职务拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：职务非法时若只提示「团队职务不合法」，
     * 管理员只能去翻代码确认到底支持哪几个词。把
     * 「member / leader / tech_lead」直接列出来，一次就能改对；
     * 文案由枚举生成，新增职务时提示自动跟着变。
     *
     * <p>为什么只拼取值、不拼中文描述：职务值是英文单词，已经可读；
     * 而数值型枚举必须带描述，因为数字本身没有语义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>只拼 value，不拼 desc。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——
     * 分隔符属于展示格式，不是稳定的协议。
     *
     * @return 形如「member / leader / tech_lead」的文案；枚举至少有一个常量，不会返回空串
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
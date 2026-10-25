package com.bhu.runshistudioweb.model.enums;

import com.bhu.runshistudioweb.constant.UserRoleConstant;

/**
 * 用户角色枚举
 *
 * 为什么必须有它：角色是权限判定的唯一依据，而权限判定的三处都是字符串——
 * @SaCheckRole(...)、注册时的默认角色、管理端传入的角色值。
 * 一旦某处写成 "Admin" 或 "admmin"，编译不会报错，
 * 表现是「明明给了管理员，却一直 A0301」，排查成本极高。
 *
 * 取值统一放在 UserRoleConstant：枚举负责「类型与校验」，
 * 常量类负责「字面量」（因为 @SaCheckRole 只接受编译期常量，两边无法共用枚举本身）。
 *
 * 三档角色的定位（与 db/user.sql 的列注释一致）：
 * - user 普通注册用户——新注册用户的默认角色。注册只代表「有了账号」，
 * C 端基础功能（浏览官网、AI 咨询）可用，但没有任何管理权限；
 * - member 工作室成员——在 user 的基础上，可以维护自己的成员档案、证书与项目
 * （对应 studio_member 等表，由后台开通，绝不随注册自动授予）；
 * - admin 管理员——管理端全部接口。
 * 注意 Sa-Token 对角色是精确匹配，不做层级包含：
 * 需要「成员或管理员都能访问」时要写
 * @SaCheckRole(value = {"member", "admin"}, mode = SaMode.OR)，
 * 只写 @SaCheckRole("member") 的话管理员也会被挡在外面。
 *
 * @author shaoshing
 */
public enum UserRoleEnum {

    /** 普通注册用户：注册后的默认角色，只有 C 端基础功能 */
    USER(UserRoleConstant.USER, "普通注册用户"),

    /** 工作室成员：可维护自己的档案、证书与项目 */
    MEMBER(UserRoleConstant.MEMBER, "工作室成员"),

    /** 管理员：可访问管理端接口 */
    ADMIN(UserRoleConstant.ADMIN, "管理员");

    /** 入库的值（与 DDL 中的 user_role 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    UserRoleEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 user_role 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的角色字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：角色是权限判定的唯一依据，写错一个字母的后果不是报错，
     * 而是「明明给了管理员，接口却一直返回无权限」。查不到返回 null 而不是抛异常，
     * 是为了让两类调用方各取所需：管理端授予角色、修改角色时必须拿返回值判空后
     * 立刻报参数错误（否则非法角色会静默入库，该账号从此任何接口都进不去）；
     * 而展示用户列表时，一条历史脏值只该让这一格显示成「未知角色」，
     * 不能让整页用户打不开。
     *
     * <p>为什么不兜底成 USER：兜底会把一个「本该是管理员但写错了」的账号
     * 悄悄降级成普通用户，本人只会觉得「权限没了」，管理员也查不出原因；
     * 反过来若兜成 ADMIN 则更危险。宁可在写入口拦住，也不要替用户猜。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较——常量在前，
     *     入参为 null 时返回 false 而不是抛 NPE；</li>
     *     <li>命中即返回，走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，"Admin" 与 "admin" 不是同一个角色。
     * 这里刻意不做忽略大小写的兜底：Sa-Token 的角色匹配也是精确比较，
     * 读路径放宽只会让「写错了大小写」这个真正的 bug 更难发现。
     *
     * @param value 待翻译的角色值，允许为 null；合法取值为 user / member / admin
     * @return 命中的枚举常量；传 null 或不是三个合法取值之一时返回 null，调用方必须判空
     */
    public static UserRoleEnum of(String value) {
        // ① 遍历全部常量：新增角色时这里不用改
        for (UserRoleEnum role : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (role.value.equals(value)) {
                return role;
            }
        }
        // 查不到返回 null：写路径据此报错，读路径据此降级
        return null;
    }

    /**
     * 按取值查找角色，查不到时退回调用方指定的兜底角色
     *
     * <p>为什么这样设计：它与 {@link #of(String)} 的差别只在「非法值不报错」。
     * 读历史数据、展示旧账号时必须用这个版本：某个账号的角色字段写错了，
     * 只该让这一格显示成兜底角色，不能让整页用户列表 500。
     * 写路径绝不能用它——把非法角色静默改成兜底值再入库，
     * 等于替管理员改了权限，而且不留痕迹，事后无法判断这个账号原本被授予了什么。
     *
     * <p>为什么不在这里写死兜底成 USER：兜底值的选择本身就是一次权限判断。
     * 展示场景兜成 USER 只是「显示成最低权限」，而如果哪天有人在权限判定里
     * 误用了本方法，兜成 USER 就意味着「认不出角色就按普通用户放行」，
     * 兜成 ADMIN 则是灾难。让调用方显式传入，能逼它在写下这一行时想清楚语义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先复用 {@link #of(String)} 查一次，保证判定规则只有一份；</li>
     *     <li>查到就返回它，查不到返回入参给的兜底角色。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「永不为 null」的前提是 defaultRole 非空。
     * 若调用方传 null，这里会原样返回 null，后面的拆箱或 getValue 照样 NPE，
     * 且栈里看不出是这里漏了兜底——默认值必须显式传枚举常量，不要传可能为空的变量。
     *
     * @param value 待翻译的角色值，允许为 null
     * @param defaultRole 查不到时使用的角色，必须非空（建议直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultRole；只有 defaultRole 为 null 时才返回 null
     */
    public static UserRoleEnum ofOrDefault(String value, UserRoleEnum defaultRole) {
        // ① 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        UserRoleEnum role = of(value);
        // ② 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return role == null ? defaultRole : role;
    }

    /**
     * 把所有合法角色拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：角色是权限判定的依据，传错时必须明确报错并告诉对方
     * 支持哪些值。提示文案若手写，很容易出现「提示说支持三种、实际只认两种」的
     * 错位——那种文案比没有提示更误导人，因为它会让人排除掉本来正确的那一项。
     * 由枚举生成则永远同源：新增角色时提示自动跟着变。
     *
     * <p>为什么只拼取值、不拼中文描述：角色值是英文单词，拼成
     * 「user / member / admin」已经足够可读；数值型枚举才必须带描述。
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
     * @return 形如「user / member / admin」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (UserRoleEnum role : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 只拼取值：角色值是英文单词，本身已经可读
            text.append(role.value);
        }
        return text.toString();
    }
}

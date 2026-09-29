package com.bhu.runshistudioweb.constant;

/**
 * 用户角色取值常量
 *
 * author: shaoshing
 *
 * <p>取值与 {@code db/user.sql} 中 {@code user_role} 的默认值及列注释、
 * 以及 {@code db/DESIGN.md} 保持一致，三处同源：
 * {@code user-普通注册用户 / member-工作室成员 / admin-管理员}。
 *
 * <p><b>为什么不直接把这些值写在 {@code UserRoleEnum} 里</b>：Java 有两条硬性限制叠在一起——
 * <ol>
 *     <li>枚举常量必须写在枚举体的最前面；</li>
 *     <li>枚举常量的构造参数**不能引用后面才声明的字段**（编译报「非法的前向引用」），
 *     而 {@code @SaCheckRole} 的属性又必须是编译期常量（不能写 {@code UserRoleEnum.USER.getValue()}）。</li>
 * </ol>
 * 于是「枚举定义」和「注解引用」没法共用同一个常量。收敛到本类后，两边都引用它，
 * 取值只有一个来源；{@code UserRoleEnum} 负责「类型与校验」，本类负责「字面量」。
 */
public final class UserRoleConstant {

    /** 普通注册用户：注册后的默认角色，只有 C 端基础功能（浏览官网、AI 咨询） */
    public static final String USER = "user";

    /** 工作室成员：可维护自己的成员档案、证书与项目 */
    public static final String MEMBER = "member";

    /** 管理员：可访问管理端全部接口 */
    public static final String ADMIN = "admin";

    /** 私有构造：纯常量类，禁止实例化 */
    private UserRoleConstant() {
    }
}

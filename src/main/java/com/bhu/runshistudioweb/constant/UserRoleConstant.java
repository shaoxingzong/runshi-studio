package com.bhu.runshistudioweb.constant;

// 用户角色取值常量
//
// 取值与 db/user.sql 中 user_role 的默认值及列注释、
// 以及 db/DESIGN.md 保持一致，三处同源：
// user-普通注册用户 / member-工作室成员 / admin-管理员。
//
// 为什么不直接把这些值写在 UserRoleEnum 里：Java 有两条硬性限制叠在一起——
// - 枚举常量必须写在枚举体的最前面；
// - 枚举常量的构造参数不能引用后面才声明的字段（编译报「非法的前向引用」），
// 而 @SaCheckRole 的属性又必须是编译期常量（不能写 UserRoleEnum.USER.getValue()）。
// 于是「枚举定义」和「注解引用」没法共用同一个常量。收敛到本类后，两边都引用它，
// 取值只有一个来源； UserRoleEnum 负责「类型与校验」，本类负责「字面量」。
public final class UserRoleConstant {

    // 普通注册用户：注册后的默认角色，只有 C 端基础功能（浏览官网、AI 咨询）
    public static final String USER = "user";

    // 工作室成员：可维护自己的成员档案、证书与项目
    public static final String MEMBER = "member";

    // 管理员：可访问管理端全部接口
    public static final String ADMIN = "admin";

    // 私有构造：纯常量类，禁止实例化
    private UserRoleConstant() {
    }
}

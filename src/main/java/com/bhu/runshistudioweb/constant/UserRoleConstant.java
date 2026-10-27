package com.bhu.runshistudioweb.constant;

/**
 * 用户角色常量
 *
 * 角色既有常量类（本类）又有枚举（UserRoleEnum），是因为 Sa-Token 的
 * @SaCheckRole("admin") 注解只接受编译期常量，不能写枚举的方法调用。
 * 所以「注解里能用的字面量」放本类，「取值字典与业务方法」放枚举。
 *
 * 两处必须保持一致：本类的值 = UserRoleEnum 的 value = db/user.sql 里 user_role 列的注释。
 * 改一处忘改另一处，后果是「明明给了角色却一直提示无权限」，而且不会报错。
 *
 * @author shaoshing
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

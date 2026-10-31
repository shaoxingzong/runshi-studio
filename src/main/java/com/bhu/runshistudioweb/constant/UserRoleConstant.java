package com.bhu.runshistudioweb.constant;

/**
 * 用户角色常量
 *
 * {@code @SaCheckRole} 只接受编译期常量，所以注解里用的字面量放本类，取值字典放枚举。
 * 两处必须与 UserRoleEnum 的 value、db/user.sql 里 user_role 的列注释保持一致。
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

package com.bhu.runshistudioweb.constant;

/**
 * 用户角色常量
 *
 * <p>为什么角色既有常量类（本类）又有枚举（UserRoleEnum）：
 * Sa-Token 的 @SaCheckRole("admin") 注解只接受<b>编译期常量</b>，
 * 不能写枚举的方法调用（如 UserRoleEnum.ADMIN.getValue()）。
 * 所以「注解里能用的字面量」放在本类，「取值字典与业务方法」放在枚举里。
 *
 * <p>两处必须保持一致：本类的值 = UserRoleEnum 的 value = db/user.sql 里
 * user_role 列的注释。改一处忘改另一处，后果是「明明给了角色却一直提示无权限」，
 * 而且不会报错，只能靠比对发现。
 *
 * <p>三档角色的定位：
 * <ul>
 *     <li>{@code user}：普通注册用户。注册即得，能浏览官网、用 AI 咨询（有配额），
 *     没有任何管理权限；</li>
 *     <li>{@code member}：工作室成员。能发帖、签到、刷题。
 *     注意这个角色<b>不单独生效</b>——成员功能的准入判据是 studio_member 表里有没有档案，
 *     角色只是让前端知道该显示哪些入口；</li>
 *     <li>{@code admin}：管理员。能进后台做全部增删改查与内容审核。</li>
 * </ul>
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

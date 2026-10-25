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
     * 按取值查找角色（大小写敏感）
     *
     * @param value 数据库中的角色值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static UserRoleEnum of(String value) {
        for (UserRoleEnum role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 按取值查找角色，取不到时返回兜底角色
     * 用于「非法值不应该让整个流程失败」的场景，例如读取历史脏数据时按最低权限处理
     *
     * @param value 数据库中的角色值
     * @param defaultRole 取不到时使用的角色
     * @return 角色枚举，永不为 null（defaultRole 由调用方保证非空）
     */
    public static UserRoleEnum ofOrDefault(String value, UserRoleEnum defaultRole) {
        UserRoleEnum role = of(value);
        return role == null ? defaultRole : role;
    }

    /**
     * 所有合法取值的可读文案，形如 user / member / admin
     *
     * 用它来拼错误提示，避免提示文案与枚举取值不同步——
     * 那种「提示说明支持 A、B，实际只认 A」的文案比没有提示更误导人。
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (UserRoleEnum role : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(role.value);
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.model.enums;

import com.bhu.runshistudioweb.constant.UserRoleConstant;

/**
 * 用户角色（sys_user.user_role）
 *
 * 字面量统一放 UserRoleConstant：@SaCheckRole 只接受编译期常量，无法直接引用枚举，
 * 枚举负责类型与校验，常量类负责字面量。取值：user / member / admin。
 *
 * 坑：Sa-Token 的角色是精确匹配、不做层级包含，要「成员或管理员」必须写
 * @SaCheckRole(value = {"member", "admin"}, mode = SaMode.OR)，只写 member 会把管理员挡在外面。
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
     * 把角色字符串翻译成枚举，查不到返回 null，绝不兜底成 USER 或 ADMIN
     *
     * 角色是权限判定的唯一依据，兜底等于替管理员改权限且不留痕迹；
     * 授予角色时必须判空报错，否则非法值入库后该账号任何接口都进不去。
     *
     * @param value 待翻译的角色值，允许为 null
     * @return 命中的枚举；null 或不是三个合法取值之一时返回 null
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
     * 只用于展示场景（一个脏值不该让整页用户列表 500）；写路径与权限判定绝不能用。
     * 不写死兜底成 USER：兜底值的选择本身就是一次权限判断，必须由调用方显式给出。
     *
     * @param value 待翻译的角色值，允许为 null
     * @param defaultRole 查不到时使用的角色，必须非空（直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultRole
     */
    public static UserRoleEnum ofOrDefault(String value, UserRoleEnum defaultRole) {
        // ① 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        UserRoleEnum role = of(value);
        // ② 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return role == null ? defaultRole : role;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「user / member / admin」
     *
     * 角色值是英文单词，只拼取值即可读；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
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

package com.bhu.runshistudioweb.model.enums;

import com.bhu.runshistudioweb.constant.UserRoleConstant;

/**
 * 用户角色（sys_user.user_role）
 *
 * @SaCheckRole(value = {"member", "admin"}, mode = SaMode.OR)，只写 member 会把管理员挡在外面。
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
     * @param value 待翻译的角色值，允许为 null
     * @return 命中的枚举；null 或不是三个合法取值之一时返回 null
     */
    public static UserRoleEnum of(String value) {
        // 遍历全部常量：新增角色时这里不用改
        for (UserRoleEnum role : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
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
     * @param value 待翻译的角色值，允许为 null
     * @param defaultRole 查不到时使用的角色，必须非空（直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultRole
     */
    public static UserRoleEnum ofOrDefault(String value, UserRoleEnum defaultRole) {
        // 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        UserRoleEnum role = of(value);
        // 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return role == null ? defaultRole : role;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「user / member / admin」
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (UserRoleEnum role : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 只拼取值：角色值是英文单词，本身已经可读
            text.append(role.value);
        }
        return text.toString();
    }
}

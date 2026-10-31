package com.bhu.runshistudioweb.model.enums;

/**
 * 成员状态（studio_member.member_status）
 *
 * @author shaoshing
 */
public enum MemberStatusEnum {

    /** 在读/在队：官网成员页默认展示的群体 */
    IN_TEAM(0, "在读/在队"),

    /** 毕业/离队：官网「已毕业成员」板块使用 */
    GRADUATED(1, "毕业/离队");

    /** 入库的值（与 DDL 中 member_status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    MemberStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 member_status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数字翻译成成员状态，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的取值，允许为 null
     * @return 命中的枚举；null 或不是 0 / 1 时返回 null
     */
    public static MemberStatusEnum of(Integer value) {
        // 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // 遍历全部常量：新增状态时这里不用改
        for (MemberStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「0（在读/在队）/ 1（毕业/离队）」
     *
     * 数字本身没有语义，必须带中文描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (MemberStatusEnum status : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：数字本身没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}
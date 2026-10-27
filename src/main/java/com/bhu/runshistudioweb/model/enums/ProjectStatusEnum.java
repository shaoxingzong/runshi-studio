package com.bhu.runshistudioweb.model.enums;

/**
 * 项目状态（studio_project.status，tinyint）
 *
 * 用 tinyint 而非 varchar：纯状态位、取值固定，且要按数值筛选（?status=1）。
 * 取值与 DDL 列注释同源：0-研发中，1-已上线，2-已结题，DDL 默认值是 1。
 *
 * 坑：不传 status 时的兜底必须取 ONLINE，不能挑 0，否则「库默认 1、代码默认 0」两套口径。
 *
 * @author shaoshing
 */
public enum ProjectStatusEnum {

    /** 研发中：还没上线，官网一般只在后台可见 */
    DEVELOPING(0, "研发中"),

    /** 已上线：官网默认展示的群体（DDL 默认值 1） */
    ONLINE(1, "已上线"),

    /** 已结题：历史项目，保留展示但不再维护 */
    ARCHIVED(2, "已结题");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ProjectStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数字翻译成项目状态，查不到返回 null，由调用方决定报错还是降级
     *
     * 写路径拿到 null 必须报错，否则非法值静默入库、之后按状态筛选再也查不出这条；
     * 展示历史数据时可降级成「未知」。null 必须先挡，否则拆箱比较会 NPE。
     *
     * @param value 待翻译的取值，允许为 null
     * @return 命中的枚举；null 或不在 0~2 时返回 null
     */
    public static ProjectStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (ProjectStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // ③ 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「0（研发中）/ 1（已上线）/ 2（已结题）」
     *
     * 数字本身没有语义，必须带中文描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ProjectStatusEnum status : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字本身没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

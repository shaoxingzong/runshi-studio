package com.bhu.runshistudioweb.model.enums;

/**
 * 举报处理状态（studio_post_report.status）
 *
 * @author shaoshing
 */
public enum ReportStatusEnum {

    /** 待处理：管理员尚未查看 */
    PENDING(0, "待处理"),

    /** 已处置：举报成立，内容已被撤下（转待审或删除） */
    RESOLVED(1, "已处置"),

    /** 举报不成立：内容保留，举报关闭 */
    DISMISSED(2, "举报不成立");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ReportStatusEnum(int value, String desc) {
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
     * 把数字翻译成举报处理状态，查不到返回 null，不兜成 PENDING
     *
     * @param value 待翻译的取值，允许为 null
     * @return 命中的枚举；null 或不在 0~2 时返回 null
     */
    public static ReportStatusEnum of(Integer value) {
        // 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // 遍历全部常量：新增状态时这里不用改
        for (ReportStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「0（待处理）/ 1（已处置）/ 2（举报不成立）」
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ReportStatusEnum status : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：这里的 1 与 2 语义相反，只看数字极易选错
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

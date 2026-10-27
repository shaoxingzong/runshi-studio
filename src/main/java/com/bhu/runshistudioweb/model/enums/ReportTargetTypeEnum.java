package com.bhu.runshistudioweb.model.enums;

/**
 * 举报目标类型（studio_post_report.target_type）
 *
 * 用 target_type + target_id 软关联，而不是给帖子与评论各建一张举报表：
 * 字段几乎一样，拆开只是复制逻辑，还让跨类型查询变成两次查询再合并。
 *
 * 坑：没有外键保证 target_id 有效，删除帖子或评论时必须在应用层一并处置其举报，
 * 否则会留下悬空举报污染待处理列表。
 *
 * @author shaoshing
 */
public enum ReportTargetTypeEnum {

    POST("post", "帖子"),

    COMMENT("comment", "评论");

    /** 入库的值（与 DDL 中 target_type 列取值一致） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ReportTargetTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 target_type 列取值一致） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把目标类型翻译成枚举，查不到返回 null，绝不猜一个默认类型
     *
     * target_id 该去哪张表查完全由本类型决定，兜成 POST 可能查到同 id 的帖子，
     * 展示出完全无关的内容且不报错——这是最坏的结果。
     *
     * @param value 待翻译的类型值，允许为 null 或空串
     * @return 命中的枚举；null、空串或未知类型时返回 null
     */
    public static ReportTargetTypeEnum of(String value) {
        // ① 先挡 null 与空串：两者都表示「没填」，没必要再遍历
        if (value == null || value.isEmpty()) {
            return null;
        }
        // ② 遍历全部常量：新增目标类型时这里不用改
        for (ReportTargetTypeEnum type : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (type.value.equals(value)) {
                return type;
            }
        }
        // ③ 查不到返回 null：调用方据此跳过或报错，绝不猜一个默认类型
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「post（帖子）/ comment（评论）」
     *
     * 英文值与中文含义的对应关系不直观，必须带描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ReportTargetTypeEnum type : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：这层对应关系并不直观
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.model.enums;

/**
 * 举报目标类型（studio_post_report.target_type）
 *
 * author: shaoshing
 *
 * <p>为什么用 varchar 存类型而不是给帖子和评论各建一张举报表：
 * 两张举报表的字段几乎完全一样（举报人、理由、处理状态、处理人），
 * 拆开只是把同一套逻辑复制两份，还让「我的举报记录」这类跨类型查询变成两次查询 + 内存合并。
 * 用 {@code target_type + target_id} 的「软关联」是这里更合适的取舍
 * ——代价是**数据库无法用外键保证 target_id 有效**，
 * 因此删除帖子/评论时必须在应用层一并处置其举报记录（不能留下悬空举报）。
 *
 * <p>取值必须与 DDL 中 {@code studio_post_report.target_type} 的列注释保持一致：
 * {@code post-帖子, comment-评论}。
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
     * 按取值查找目标类型
     *
     * @param value 数据库中的类型值
     * @return 匹配的枚举；无匹配（含 null、空串）时返回 null
     */
    public static ReportTargetTypeEnum of(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (ReportTargetTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案
     *
     * @return 以「 / 」分隔的合法取值（含中文含义）
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ReportTargetTypeEnum type : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.model.enums;

/**
 * 举报处理状态（studio_post_report.status）
 *
 * author: shaoshing
 *
 * <p><b>为什么「已处置」与「举报不成立」要分成两个而不是合并成一个「已处理」</b>：
 * 这两者对内容的处置完全相反——前者内容被撤下（转待审或删除），后者内容保留原样。
 * 合并成一个的话，事后复盘就无法回答「我们的举报里有多少是真的有问题」，
 * 而这恰恰是判断「免审策略是否过松」的唯一依据：
 * 若「举报不成立」长期占绝大多数，说明免审给对了人；
 * 若「已处置」占比突然升高，说明信任等级的阈值需要收紧。
 *
 * <p>取值必须与 DDL 中 {@code studio_post_report.status} 的列注释保持一致：
 * {@code 0-待处理, 1-已处置, 2-举报不成立}。
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
     * 按取值查找处理状态
     *
     * @param value 数据库中的状态值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static ReportStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (ReportStatusEnum status : values()) {
            if (status.value == value) {
                return status;
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
        for (ReportStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

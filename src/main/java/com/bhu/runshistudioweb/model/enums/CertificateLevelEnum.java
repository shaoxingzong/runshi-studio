package com.bhu.runshistudioweb.model.enums;

/**
 * 证书「级别」维度枚举（对应 studio_certificate.award_level）
 *
 * author: shaoshing
 *
 * <p><b>为什么级别和类型是两套枚举（ADR-5）</b>：初版设计里曾把「国家级竞赛 / 软著」这种
 * 「级别 + 类型」合并进一个字段，结果是**任一维度都无法独立筛选与统计**——
 * 想查「所有国家级证书」要写 {@code LIKE '%国家级%'}，既走不了索引，
 * 又会误匹配到「国家级软著」与「国家级竞赛」之外的写法；想统计「有多少竞赛」同样无解。
 * 拆成两个正交字段后：
 * <ul>
 *     <li>按级别筛选 → 走 {@code idx_level_date (award_level, award_date DESC)}；</li>
 *     <li>按类型筛选 → 走 {@code idx_type_date (award_type, award_date DESC)}。</li>
 * </ul>
 * 两个维度各自独立，也能自由组合（国家级 + 软著、省级 + 竞赛……）。
 *
 * <p><b>维护约定</b>：新增级别时要同步改 DDL 的列注释（DDL 注释即取值字典），
 * 否则会出现「枚举里加了、库里没写」，后人按注释写代码就会漏值。
 */
public enum CertificateLevelEnum {

    /** 国家级 */
    NATIONAL("national", "国家级"),

    /** 省级 */
    PROVINCIAL("provincial", "省级"),

    /** 市级 / 校级 */
    MUNICIPAL("municipal", "市级/校级");

    /** 入库的值（与 DDL 中的 award_level 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    CertificateLevelEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 award_level 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找级别（大小写敏感）
     *
     * @param value 数据库中的级别值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static CertificateLevelEnum of(String value) {
        for (CertificateLevelEnum level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 按取值查找级别，取不到时返回兜底级别
     *
     * @param value          数据库中的级别值
     * @param defaultLevel   取不到时使用的级别
     * @return 级别枚举，永不为 null（defaultLevel 由调用方保证非空）
     */
    public static CertificateLevelEnum ofOrDefault(String value, CertificateLevelEnum defaultLevel) {
        CertificateLevelEnum level = of(value);
        return level == null ? defaultLevel : level;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code national / provincial / municipal}
     *
     * <p>用它拼错误提示，避免提示文案与枚举取值不同步。
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateLevelEnum level : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(level.value);
        }
        return text.toString();
    }
}

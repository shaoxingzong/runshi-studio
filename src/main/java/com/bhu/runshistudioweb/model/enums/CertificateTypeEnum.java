package com.bhu.runshistudioweb.model.enums;

/**
 * 证书「类型」维度枚举（对应 studio_certificate.award_type）
 *
 * author: shaoshing
 *
 * <p>与 {@link CertificateLevelEnum} 是**正交的两个维度**：级别回答「含金量多高」，
 * 类型回答「这是什么成果」。两者组合起来才能完整描述一条记录，例如
 * 「国家级 + 软著」「省级 + 竞赛」。
 *
 * <p>为什么用 {@code varchar} 而不是 {@code tinyint} 存类型（DESIGN.md 4.2 的判定标准）：
 * <ul>
 *     <li>取值会扩展——以后可能加「实用新型」「外观设计」「会议论文」等，
 *     用 varchar 只需加枚举值，不必改表结构，也不用担心数字含义失忆；</li>
 *     <li>这些值要直接参与接口返回与筛选，排障时能一眼读懂。</li>
 * </ul>
 * 反过来，{@code user_status}、{@code member_status} 这类「取值固定、不对外展示」的状态位才用 tinyint。
 */
public enum CertificateTypeEnum {

    /** 学科竞赛 */
    COMPETITION("competition", "学科竞赛"),

    /** 软件著作权 */
    SOFT_COPYRIGHT("soft_copyright", "软件著作权"),

    /** 专利 */
    PATENT("patent", "专利"),

    /** 论文 */
    PAPER("paper", "论文");

    /** 入库的值（与 DDL 中的 award_type 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    CertificateTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 award_type 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找类型（大小写敏感）
     *
     * @param value 数据库中的类型值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static CertificateTypeEnum of(String value) {
        for (CertificateTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 按取值查找类型，取不到时返回兜底类型
     *
     * @param value        数据库中的类型值
     * @param defaultType  取不到时使用的类型
     * @return 类型枚举，永不为 null（defaultType 由调用方保证非空）
     */
    public static CertificateTypeEnum ofOrDefault(String value, CertificateTypeEnum defaultType) {
        CertificateTypeEnum type = of(value);
        return type == null ? defaultType : type;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code competition / soft_copyright / patent / paper}
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateTypeEnum type : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(type.value);
        }
        return text.toString();
    }
}

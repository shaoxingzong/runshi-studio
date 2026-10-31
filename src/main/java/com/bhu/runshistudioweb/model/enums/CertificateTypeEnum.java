package com.bhu.runshistudioweb.model.enums;

/**
 * 证书类型（studio_certificate.award_type）
 *
 * @author shaoshing
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
     * 把类型字符串翻译成枚举，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的类型值，允许为 null
     * @return 命中的枚举；null 或不在四个合法取值内时返回 null
     */
    public static CertificateTypeEnum of(String value) {
        // 遍历全部常量：新增类型时这里不用改
        for (CertificateTypeEnum type : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (type.value.equals(value)) {
                return type;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 按取值查找类型，查不到时退回调用方指定的兜底类型
     *
     * @param value 待翻译的类型值，允许为 null
     * @param defaultType 查不到时使用的类型，必须非空（直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultType
     */
    public static CertificateTypeEnum ofOrDefault(String value, CertificateTypeEnum defaultType) {
        // 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        CertificateTypeEnum type = of(value);
        // 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return type == null ? defaultType : type;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「competition / soft_copyright / patent / paper」
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateTypeEnum type : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 只拼取值：类型值是英文单词，本身已经可读
            text.append(type.value);
        }
        return text.toString();
    }
}

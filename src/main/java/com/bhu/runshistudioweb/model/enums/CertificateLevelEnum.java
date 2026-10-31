package com.bhu.runshistudioweb.model.enums;

/**
 * 证书级别（studio_certificate.award_level）
 *
 * @author shaoshing
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
     * 把级别字符串翻译成枚举，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的级别值，允许为 null
     * @return 命中的枚举；null 或不在三个合法取值内时返回 null
     */
    public static CertificateLevelEnum of(String value) {
        // 遍历全部常量：新增级别时这里不用改
        for (CertificateLevelEnum level : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (level.value.equals(value)) {
                return level;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 按取值查找级别，查不到时退回调用方指定的兜底级别
     *
     * @param value 待翻译的级别值，允许为 null
     * @param defaultLevel 查不到时使用的级别，必须非空（直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultLevel
     */
    public static CertificateLevelEnum ofOrDefault(String value, CertificateLevelEnum defaultLevel) {
        // 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        CertificateLevelEnum level = of(value);
        // 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return level == null ? defaultLevel : level;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「national / provincial / municipal」
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateLevelEnum level : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 只拼取值：级别值是英文单词，本身已经可读
            text.append(level.value);
        }
        return text.toString();
    }
}

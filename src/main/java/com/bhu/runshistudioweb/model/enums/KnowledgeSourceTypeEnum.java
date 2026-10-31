package com.bhu.runshistudioweb.model.enums;

/**
 * 知识库文档来源类型（studio_knowledge_doc.source_type，varchar(16)）
 *
 * @author shaoshing
 */
public enum KnowledgeSourceTypeEnum {

    /** 手工录入：没有对应的业务数据（source_id 为 null），每次调用都新建一篇文档 */
    MANUAL("manual", "手工录入"),

    /** 项目案例：来源为 studio_project */
    PROJECT("project", "项目案例"),

    /** 成员档案：来源为 studio_member */
    MEMBER("member", "成员档案"),

    /** 荣誉证书：来源为 studio_certificate */
    CERTIFICATE("certificate", "荣誉证书");

    /** 入库的值（与 DDL 中 source_type 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    KnowledgeSourceTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 source_type 列取值一致） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把来源类型字符串翻译成枚举，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的来源类型值，允许为 null
     * @return 命中的枚举；null 或不在四个合法取值内时返回 null
     */
    public static KnowledgeSourceTypeEnum of(String value) {
        // 遍历全部常量：新增来源时这里不用改
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (sourceType.value.equals(value)) {
                return sourceType;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「manual（手工录入）/ project（项目案例）」
     *
     * 英文值与中文含义的对应关系不直观，必须带描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：这层对应关系并不直观
            text.append(sourceType.value).append("（").append(sourceType.desc).append("）");
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.model.enums;

/**
 * 知识库文档来源类型枚举（studio_knowledge_doc.source_type，varchar(16)）
 *
 * author: shaoshing
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code source_type} 的列注释保持一致：
 * {@code manual-手工录入, project-项目案例, member-成员档案, certificate-荣誉证书}。
 * 三处同源：DDL 列注释 = 本枚举 = db/DESIGN.md 的取值清单。
 *
 * <p>写出这个枚举的直接理由，是它在三个地方会被当成「分支依据」：
 * <ul>
 *     <li><b>拼正文</b>：不同来源的正文模板不同（项目取标题/摘要/正文，成员取姓名/届别/…）；</li>
 *     <li><b>反查业务数据</b>：检索命中后要靠它决定 {@code source_id} 去哪张表查（R2 溯源）；</li>
 *     <li><b>合法性校验</b>：{@code /knowledge/doc/sync} 只接受 project/member/certificate，
 *     {@code manual} 必须走「手工录入」接口（它没有业务来源）。</li>
 * </ul>
 * 用魔法字符串写这三处，早晚出现「某个分支拼错一个字母，静默走 default」的事故——
 * 那正是本枚举要消灭的东西（与 {@link AiMessageRoleEnum} 同一套写法）。
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
     * 按取值查找来源类型
     *
     * @param value 数据库中的来源类型值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static KnowledgeSourceTypeEnum of(String value) {
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            if (sourceType.value.equals(value)) {
                return sourceType;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如
     * {@code manual（手工录入） / project（项目案例） / member（成员档案） / certificate（荣誉证书）}
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(sourceType.value).append("（").append(sourceType.desc).append("）");
        }
        return text.toString();
    }
}

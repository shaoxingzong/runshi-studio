package com.bhu.runshistudioweb.model.enums;

/**
 * 知识库文档来源类型枚举（studio_knowledge_doc.source_type，varchar(16)）
 *
 * 取值必须与 db/user.sql 中 source_type 的列注释保持一致：
 * manual-手工录入, project-项目案例, member-成员档案, certificate-荣誉证书。
 * 两处同源：DDL 列注释 = 本枚举。
 *
 * 写出这个枚举的直接理由，是它在三个地方会被当成「分支依据」：
 * - 拼正文：不同来源的正文模板不同（项目取标题/摘要/正文，成员取姓名/届别/…）；
 * - 反查业务数据：检索命中后要靠它决定 source_id 去哪张表查（R2 溯源）；
 * - 合法性校验：/knowledge/doc/sync 只接受 project/member/certificate，
 * manual 必须走「手工录入」接口（它没有业务来源）。
 * 用魔法字符串写这三处，早晚出现「某个分支拼错一个字母，静默走 default」的事故——
 * 那正是本枚举要消灭的东西（与 AiMessageRoleEnum 同一套写法）。
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
     * 把数据库或前端传来的来源类型字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为这个枚举被用在
     * 两条要求相反的路径上。写路径（同步文档、手工录入）必须拿到合法枚举，
     * 因为后续要靠它决定 source_id 去哪张业务表查，未知来源意味着
     * 「不知道该查哪里」，只能报参数错误；读路径（列出文档、展示溯源信息）
     * 遇到历史脏值应当降级展示，不能让整页报错——一条文档的来源写错了，
     * 不该连带其它几十条一起打不开。把选择权交给调用方，它才知道当前是哪条路径。
     *
     * <p>为什么用遍历而不是 switch：新增来源（例如「活动记录」）只要加一行常量，
     * 本方法一个字都不用改；switch 漏写 case 不会编译报错，只会静默查成 null，
     * 表现是「新来源的文档全都溯源失败」，而故障点极难定位到这一个方法。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较——常量在前，
     *     入参为 null 时返回 false 而不是抛 NPE；</li>
     *     <li>命中即返回，走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，"Project" 与 "project" 不是同一个来源。
     * 这里不做忽略大小写的兜底：大小写由写路径决定，读路径擅自放宽会让
     * 写错的值永远暴露不出来；要放宽应当改在写路径统一转小写。
     *
     * @param value 待翻译的来源类型值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或不是四个合法取值之一时返回 null，调用方必须判空
     */
    public static KnowledgeSourceTypeEnum of(String value) {
        // ① 遍历全部常量：新增来源时这里不用改
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (sourceType.value.equals(value)) {
                return sourceType;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 把所有合法来源拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：来源类型是闭集，传了枚举外的值必须明确报错，
     * 而不是静默返回空列表——后者会让管理员以为「库里没有这类文档」，
     * 排查方向被彻底带偏。既然要报错，就得一次说清合法取值；
     * 文案由枚举生成而不是手写，是为了让提示与取值永远同源，
     * 新增来源时提示自动跟着变。
     *
     * <p>为什么带中文描述：来源值虽然是英文单词，但「manual 是手工录入、
     * project 是项目案例」这层对应关系并不直观；带上描述后，
     * 报错信息本身就能告诉调用方该传哪个词。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>每个元素拼成「取值（中文描述）」。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——
     * 分隔符与括号属于展示格式，随时可能调整，不是稳定的协议。
     *
     * @return 形如「manual（手工录入）/ project（项目案例）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (KnowledgeSourceTypeEnum sourceType : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：这层对应关系并不直观
            text.append(sourceType.value).append("（").append(sourceType.desc).append("）");
        }
        return text.toString();
    }
}

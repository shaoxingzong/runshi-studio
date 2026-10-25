package com.bhu.runshistudioweb.model.enums;

/**
 * AI 消息角色枚举（studio_ai_message.role，varchar(16)）
 *
 * 取值必须与 db/user.sql 中 role 的列注释保持一致：
 * user-用户提问, assistant-AI 回答。
 *
 * 为什么用 enum 而不是常量类（对比 com.bhu.runshistudioweb.constant.UserRoleConstant）：
 * 那个常量类存在的原因是「 @SaCheckRole 的注解属性必须是编译期常量」，
 * 而枚举的构造参数无法引用后面才声明的字段，两者只能拆开；本模块的 role 没有任何注解引用它，
 * 所以直接用一个枚举即可，取值、校验、文案三件事都在一处。
 *
 * 为什么不照抄 OpenAI 协议里的 "assistant" / "user" 字面量：
 * 字面量散落在写库、拼 prompt、解析响应三处，任何一处拼错都是「AI 记不住上一句」这类
 * 不报错的静默故障。统一走枚举的 getValue，拼写只有一处来源。
 *
 * @author shaoshing
 */
public enum AiMessageRoleEnum {

    /** 用户提问（落库时写入） */
    USER("user", "用户提问"),

    /** AI 回答（落库时写入，同时原样送给模型作为上下文） */
    ASSISTANT("assistant", "AI 回答");

    /** 入库的值（与 DDL 中 role 列取值、以及 OpenAI 兼容协议的 role 字段一致） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    AiMessageRoleEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 role 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或模型响应里的角色字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：这个方法的两个调用场景对「不认识的值」要求相反。
     * 拼上下文给模型时（AiManager），角色必须是 user 或 assistant 之一，
     * 查到 null 说明历史数据坏了，应当报错并指出合法取值；
     * 而纯展示场景（会话详情列出消息）遇到脏值只应降级，不能让整段会话打不开。
     * 所以这里不抛异常、也不擅自兜成 USER——兜底会把「数据有问题」伪装成
     * 「这是一句用户提问」，故障被藏起来，事后无从追查。
     *
     * <p>为什么用遍历而不是 switch：新增一种角色（例如将来接入工具调用需要的 tool）
     * 只要加一行常量，本方法不用改；switch 漏写 case 不会编译报错，只会静默返回 null。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 去和入参比较——比较方向是
     *     {@code role.value.equals(value)}，常量在前，天然免掉一次判空；</li>
     *     <li>命中即返回；全部比完仍不相等则返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，"Assistant" 与 "assistant" 不是同一个角色。
     * 这里刻意不做忽略大小写的兜底：数据库里的大小写是写路径决定的，
     * 读路径擅自放宽会让「写错了」永远暴露不出来。若确实要放宽，
     * 应当改在写路径统一小写，而不是在读路径做两套规则。
     *
     * @param value 待翻译的角色值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或不是 user / assistant 时返回 null，调用方必须判空
     */
    public static AiMessageRoleEnum of(String value) {
        // ① 遍历全部常量：新增角色时这里不用改
        for (AiMessageRoleEnum role : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (role.value.equals(value)) {
                return role;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 把所有合法角色拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：拼上下文给模型时若发现角色非法，报错必须告诉对方
     * 「哪些值才是对的」，否则排查只能去翻源码。文案由枚举生成而不是手写，
     * 是为了让提示与取值永远同源：将来加一个角色，提示自动跟着变。
     *
     * <p>为什么拼成「user（用户提问）」而不是只拼取值：这两个词对刚接触
     * OpenAI 兼容协议的人是陌生的，加上中文描述后，报错信息本身就能自解释。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个分隔符；</li>
     *     <li>每个元素拼成「取值（中文描述）」。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析成枚举——
     * 分隔符与括号属于展示格式，随时可能调整，不是协议的一部分。
     *
     * @return 形如「user（用户提问）/ assistant（AI 回答）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiMessageRoleEnum role : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：这两个词对刚接触协议的人并不直观
            text.append(role.value).append("（").append(role.desc).append("）");
        }
        return text.toString();
    }
}

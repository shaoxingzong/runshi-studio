package com.bhu.runshistudioweb.model.enums;

/**
 * AI 消息角色枚举（studio_ai_message.role，varchar(16)）
 *
 * author: shaoshing
 *
 * <p>取值必须与 {@code db/user.sql} 中 {@code role} 的列注释保持一致：
 * {@code user-用户提问, assistant-AI 回答}。
 *
 * <p><b>为什么用 enum 而不是常量类</b>（对比 {@link com.bhu.runshistudioweb.constant.UserRoleConstant}）：
 * 那个常量类存在的原因是「{@code @SaCheckRole} 的注解属性必须是编译期常量」，
 * 而枚举的构造参数无法引用后面才声明的字段，两者只能拆开；本模块的 role 没有任何注解引用它，
 * 所以直接用一个枚举即可，取值、校验、文案三件事都在一处。
 *
 * <p><b>为什么不照抄 OpenAI 协议里的 {@code "assistant"} / {@code "user"} 字面量</b>：
 * 字面量散落在写库、拼 prompt、解析响应三处，任何一处拼错都是「AI 记不住上一句」这类
 * 不报错的静默故障。统一走枚举的 {@link #getValue()}，拼写只有一处来源。
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
     * 按取值查找角色
     *
     * @param value 数据库中的角色值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static AiMessageRoleEnum of(String value) {
        for (AiMessageRoleEnum role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code user（用户提问） / assistant（AI 回答）}
     *
     * @return 以「 / 」分隔的合法取值
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiMessageRoleEnum role : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(role.value).append("（").append(role.desc).append("）");
        }
        return text.toString();
    }
}

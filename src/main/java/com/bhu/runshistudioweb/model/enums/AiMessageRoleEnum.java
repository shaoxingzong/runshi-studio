package com.bhu.runshistudioweb.model.enums;

/**
 * AI 消息角色（studio_ai_message.role，varchar(16)）
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
     * 把角色字符串翻译成枚举，查不到返回 null，不擅自兜成 USER
     *
     * @param value 待翻译的角色值，允许为 null
     * @return 命中的枚举；null 或不是 user / assistant 时返回 null
     */
    public static AiMessageRoleEnum of(String value) {
        // 遍历全部常量：新增角色时这里不用改
        for (AiMessageRoleEnum role : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (role.value.equals(value)) {
                return role;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法角色文案，如「user（用户提问）/ assistant（AI 回答）」
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiMessageRoleEnum role : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：这两个词对刚接触协议的人并不直观
            text.append(role.value).append("（").append(role.desc).append("）");
        }
        return text.toString();
    }
}

package com.bhu.runshistudioweb.constant;

/**
 * AI 咨询模块常量
 *
 * @author shaoshing
 */
public final class AiChatConstant {

    /** 上下文窗口：送给模型的「最近 N 条历史消息」 */
    public static final int CONTEXT_MESSAGE_COUNT = 10;

    /** 历史接口返回的最大消息条数（最近 50 条，时间正序） */
    public static final int HISTORY_MESSAGE_LIMIT = 50;

    /** 会话标题截断长度：取首问的前 30 个字 */
    public static final int SESSION_TITLE_MAX_LENGTH = 30;

    /** 单次提问的最大长度（与 AiChatRequest 上的 @Size 保持一致） */
    public static final int MESSAGE_MAX_LENGTH = 500;

    /** 私有构造：纯常量类，禁止实例化（阿里规约） */
    private AiChatConstant() {
    }
}

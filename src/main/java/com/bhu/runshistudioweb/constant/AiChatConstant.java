package com.bhu.runshistudioweb.constant;

/**
 * AI 咨询模块常量
 *
 * 这几个数字都是「产品口径」而不是实现细节：调整时（例如窗口从 10 条放到 20 条）
 * 必须能一眼看到它们定义在哪、且只有一个来源。散落成字面量时最典型的翻车是
 * 「改了 Service 忘了改测试」。
 *
 * 提问次数上限不在这里：它属于运行期可调的配置（studio.ai.query-limit），
 * 放在配置里才能不改代码就调整。
 *
 * @author shaoshing
 */
public final class AiChatConstant {

    /**
     * 上下文窗口：送给模型的「最近 N 条历史消息」
     *
     * 不是越大越好：窗口越大，token 成本越高、响应越慢，而且早期消息与当前问题的
     * 相关性通常很低。10 条＝最近 5 轮对话，对官网问答场景足够。
     */
    public static final int CONTEXT_MESSAGE_COUNT = 10;

    /**
     * 历史接口返回的最大消息条数（最近 50 条，时间正序）
     *
     * 匿名接口必须有上限：消息会随对话轮次无限增长，不设上限时前端一打开历史页
     * 就会把整个会话拉回浏览器。要「向上翻更多」应改成游标分页，而不是放大这个数字。
     */
    public static final int HISTORY_MESSAGE_LIMIT = 50;

    /**
     * 会话标题截断长度：取首问的前 30 个字
     *
     * 标题只给会话列表展示，太长前端还得再截一次。与 DDL 中 title varchar(64) 的关系是
     * 「业务截断 30、列宽留一倍余量」，以后放宽到 60 个字也不用改表。
     */
    public static final int SESSION_TITLE_MAX_LENGTH = 30;

    /**
     * 单次提问的最大长度（与 AiChatRequest 上的 @Size 保持一致）
     *
     * 两道校验必须同时存在：注解负责 Web 层的第一道拦截，常量负责 Service 被非 Web 入口
     * （定时任务、测试）直接调用时的兜底。
     */
    public static final int MESSAGE_MAX_LENGTH = 500;

    /** 私有构造：纯常量类，禁止实例化（阿里规约） */
    private AiChatConstant() {
    }
}

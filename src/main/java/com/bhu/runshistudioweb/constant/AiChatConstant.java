package com.bhu.runshistudioweb.constant;

/**
 * AI 咨询模块常量
 *
 * author: shaoshing
 *
 * <p>为什么单独建一个常量类而不是散在各处写魔法值：
 * 这三个数字都是「产品口径」而不是「实现细节」——上下文窗口、历史条数、标题截断长度
 * 一旦要调整（例如把窗口从 10 条放到 20 条），必须能一眼看到它们都定义在哪、
 * 并且只有一个来源。散落成字面量时最典型的翻车是「改了 Service 忘了改测试」。
 *
 * <p>与 {@link UserRoleConstant} 的分工：那边是「闭集取值」（要被注解引用，必须是编译期字符串），
 * 本类只有数值型口径参数，所以没有配套枚举。
 *
 * <p>注意：{@code query-limit}（提问次数上限）<b>不在这里</b>——
 * 它属于运行期可调的配置（见 {@code studio.ai.query-limit}），放在配置里才能不改代码就调整。
 */
public final class AiChatConstant {

    /**
     * 上下文窗口：送给模型的「最近 N 条历史消息」
     *
     * <p>不是越大越好：窗口越大，token 成本越高、响应越慢，而且早期消息与当前问题的
     * 相关性通常很低。10 条＝最近 5 轮对话，对官网问答场景足够。
     */
    public static final int CONTEXT_MESSAGE_COUNT = 10;

    /**
     * 历史接口返回的最大消息条数（最近 50 条，时间正序）
     *
     * <p>匿名接口必须有上限：会话消息会随对话轮次无限增长，
     * 不设上限时前端一打开历史页就会把整个会话拉回浏览器。
     * 要做「向上翻更多历史」时，应改成游标分页而不是把这个数字放大。
     */
    public static final int HISTORY_MESSAGE_LIMIT = 50;

    /**
     * 会话标题截断长度：取首问的前 30 个字
     *
     * <p>标题是给「会话列表」展示用的，太长反而要前端再截一次；
     * 与 DDL 中 {@code title varchar(64)} 的关系是「业务截断 30，列宽留一倍余量」——
     * 万一以后放宽到 60 个字也不用改表。
     */
    public static final int SESSION_TITLE_MAX_LENGTH = 30;

    /**
     * 单次提问的最大长度（与 {@code AiChatRequest} 上的 {@code @Size} 保持一致）
     *
     * <p>两道校验必须同时存在：注解负责 Web 层的第一道拦截，
     * 常量负责 Service 被非 Web 入口（定时任务、测试）直接调用时的兜底。
     */
    public static final int MESSAGE_MAX_LENGTH = 500;

    /** 私有构造：纯常量类，禁止实例化（阿里规约） */
    private AiChatConstant() {
    }
}

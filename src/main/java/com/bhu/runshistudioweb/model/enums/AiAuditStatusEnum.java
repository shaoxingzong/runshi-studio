package com.bhu.runshistudioweb.model.enums;

/**
 * AI 内容审核结论（帖子与评论共用）
 *
 * 它记录的是「机器判了什么」，与 AuditStatusEnum（内容当前的实际状态）是两列，
 * 不可以互相替代：
 * - AuditStatusEnum 是结果：内容此刻是待审、已通过还是已驳回；
 * - 本枚举是来源与把握：这个结果是机器判的、判得有多确定，还是根本没判成。
 * 例如「AI 判安全」会让 status 变成已通过，但审计时必须能说出
 * 「这条是机器放的、没人看过」——只留 status 一列就说不清这句话。
 *
 * 为什么「未判（0）」与「判定失败（4）」要分成两个值：
 * 合成一个的话，管理端无法区分「还没轮到它」和「AI 挂了」。
 * 前者只是排队中，后者是需要运维介入的信号——把故障信号藏进「排队中」是最难发现的一类问题。
 *
 * 为什么要有「灰色（3）」这一档：模型不是全知全能，它对一部分内容的判断本身就不确定。
 * 逼它在「放行」和「驳回」里二选一，等于把不确定性硬转成误判；
 * 留一档「我说不准，交给人」才是这套机制减负又不失守的关键（业界称「三分法」）。
 *
 * 取值必须与 DDL 中 studio_post.ai_audit_status 与
 * studio_post_comment.ai_audit_status 的列注释保持一致。
 *
 * @author shaoshing
 */
public enum AiAuditStatusEnum {

    /** 未判：还没送审，或已提交但异步任务尚未执行 */
    NOT_JUDGED(0, "未判"),

    /** 安全：AI 确信没问题，已自动通过并公开 */
    SAFE(1, "安全（自动通过）"),

    /** 违规：AI 确信有问题，已自动驳回 */
    BLOCKED(2, "违规（自动驳回）"),

    /** 灰色：AI 拿不准，保持待审转人工 */
    REVIEW(3, "灰色（待人工）"),

    /** 判定失败：模型未配置 / 超时 / 报错 / 输出无法解析，保持待审转人工 */
    FAILED(4, "判定失败（待人工）");

    /** 入库的值（与 DDL 中 ai_audit_status 列取值一致） */
    private final int value;

    /** 中文描述，用于管理端展示与日志 */
    private final String desc;

    AiAuditStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 ai_audit_status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于管理端展示与日志 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的数字翻译成审核结论，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为同一个方法有两类调用方，
     * 而它们对「不认识的值」的处置恰好相反。写路径（管理端按结论筛选、落库前校验）
     * 拿到 null 应当立刻报参数错误，否则非法值会静默写进库；读路径（管理端列表展示
     * AI 结论、日志还原）拿到 null 只应降级成「未知」，不能让一条脏数据把整页打成 500。
     * 把「报错还是降级」的决定权留给唯一知道上下文的调用方，比在这里替它选一种行为更安全。
     * 也正因为如此，这里不给默认值：默认值一旦写死，「还没送审」与「脏数据」就再也分不开了。
     *
     * <p>为什么用遍历而不是 switch：新增一个结论只要加一行枚举常量，本方法一个字都不用改；
     * switch 则要补 case，漏补时编译器不会报错（普通 switch 语句对枚举没有穷尽性检查），
     * 表现是新值永远查成 null。遍历的代价可以忽略——常量只有五个。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：Integer 是包装类型，前端不传参数、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会直接 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍没命中就返回 null，把处置权交回调用方。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code status.value == value} 之所以安全，是因为左边是 int、
     * 右边是 Integer，比较时右边会自动拆箱成数值；如果两边都是 Integer，
     * {@code ==} 比的会是对象引用，只有 -128~127 的缓存值碰巧相等，
     * 取值一旦超出这个范围就会出现「明明有却查不到」的怪现象。
     *
     * @param value 待翻译的取值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或取值不在 0~4 之间时返回 null，调用方必须判空
     */
    public static AiAuditStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是完全正常的输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增结论时这里不用改，常量列表自己会变长
        for (AiAuditStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而不是对象引用
            if (status.value == value) {
                return status;
            }
        }
        // ③ 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 把所有合法取值拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：参数非法时如果只提示「审核结论不合法」，前端和用户只能靠猜；
     * 把全部取值连中文含义一起列出来，对方一眼就能改对。文案由枚举自己生成，
     * 新增取值时提示自动跟着变，不会出现「提示说支持三种、实际只认两种」的错位——
     * 那种文案比没有提示更误导人，因为它会让人排除掉本来正确的那一项。
     *
     * <p>为什么带中文描述：数字单独看没有语义，用户看到「3」并不知道它代表
     * 「灰色（待人工）」；把描述一起拼上，报错信息本身就成了取值字典。
     * 对字符串取值的枚举（例如职务 member/leader）则只拼值，因为值本身已经可读。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加元素，只在「已经拼过内容」时补分隔符——这样末尾不会多出一个
     *     「 / 」，也不必额外维护下标变量来判断是不是第一个；</li>
     *     <li>每个元素拼成「取值（中文描述）」，用全角括号包住描述，与中文正文混排时更易读。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——分隔符、括号、顺序
     * 随时可能为了可读性调整，解析它等于把展示格式当成了协议。
     * 另外 {@code values()} 每次调用都会克隆一份常量数组，本方法只在报错路径上执行，
     * 不必担心性能，但不要把它放进大循环里反复拼。
     *
     * @return 形如「0（未判）/ 1（安全（自动通过））」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiAuditStatusEnum status : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字单独看没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

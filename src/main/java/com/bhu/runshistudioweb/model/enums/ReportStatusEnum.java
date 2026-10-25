package com.bhu.runshistudioweb.model.enums;

/**
 * 举报处理状态（studio_post_report.status）
 *
 * 为什么「已处置」与「举报不成立」要分成两个而不是合并成一个「已处理」：
 * 这两者对内容的处置完全相反——前者内容被撤下（转待审或删除），后者内容保留原样。
 * 合并成一个的话，事后复盘就无法回答「我们的举报里有多少是真的有问题」，
 * 而这恰恰是判断「免审策略是否过松」的唯一依据：
 * 若「举报不成立」长期占绝大多数，说明免审给对了人；
 * 若「已处置」占比突然升高，说明信任等级的阈值需要收紧。
 *
 * 取值必须与 DDL 中 studio_post_report.status 的列注释保持一致：
 * 0-待处理, 1-已处置, 2-举报不成立。
 *
 * @author shaoshing
 */
public enum ReportStatusEnum {

    /** 待处理：管理员尚未查看 */
    PENDING(0, "待处理"),

    /** 已处置：举报成立，内容已被撤下（转待审或删除） */
    RESOLVED(1, "已处置"),

    /** 举报不成立：内容保留，举报关闭 */
    DISMISSED(2, "举报不成立");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ReportStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的数字翻译成举报处理状态，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为两条路径要求相反。
     * 管理端按状态筛选举报时，传一个 9 进来必须报参数错误——否则会返回空列表，
     * 管理员会以为「没有待处理的举报」，从而漏掉真正需要处置的内容。
     * 而展示举报详情时，历史脏值只该让这一格显示成「未知」，不该让整页打不开。
     *
     * <p>为什么不兜底成 PENDING：兜底会让已关闭的举报重新出现在待处理列表里，
     * 管理员会重复处置同一条，作者也可能收到重复通知。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：GET 参数不传、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code status.value == value} 能正确比较，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱；若字段改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有缓存范围内的值碰巧相等。
     *
     * @param value 待翻译的取值，允许为 null；合法取值为 0 / 1 / 2
     * @return 命中的枚举常量；传 null 或取值不在 0~2 之间时返回 null，调用方必须判空
     */
    public static ReportStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (ReportStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (status.value == value) {
                return status;
            }
        }
        // ③ 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 把所有合法状态拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：举报状态是数字，管理端筛选时传错若只提示
     * 「状态不合法」，调用方不知道 0/1/2 各是什么。把
     * 「0（待处理）/ 1（已处置）/ 2（举报不成立）」列出来，一次就能改对；
     * 文案由枚举生成，新增状态时提示自动跟着变。
     *
     * <p>为什么带中文描述：数字本身没有语义。尤其这里的 1 和 2
     * 语义相反（处置了 / 不成立），只看数字极容易选错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>每个元素拼成「取值（中文描述）」。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——
     * 分隔符与括号属于展示格式，不是稳定的协议。
     *
     * @return 形如「0（待处理）/ 1（已处置）/ 2（举报不成立）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ReportStatusEnum status : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：这里的 1 与 2 语义相反，只看数字极易选错
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

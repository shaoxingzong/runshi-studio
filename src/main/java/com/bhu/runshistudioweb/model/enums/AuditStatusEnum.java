package com.bhu.runshistudioweb.model.enums;

/**
 * 内容审核状态（帖子与评论共用）
 *
 * 为什么帖子与评论共用一套枚举，而不是各建一个
 * （ PostStatusEnum / CommentStatusEnum）：
 * 两者的状态机完全一致——「待审 → 已通过 / 已驳回」，取值语义也一模一样。
 * 建两个只会让将来加状态时改两处、还容易改漏一处。
 * 真正需要分开的是各自的业务动作（帖子能置顶、评论能级联删除），那是代码层面的差异，
 * 不是状态取值层面的差异。
 *
 * 取值必须与 DDL 中 studio_post.status 与 studio_post_comment.status
 * 的列注释保持一致：0-待审, 1-已通过, 2-已驳回。
 *
 * 默认态必须是「待审（0）」而不是「已通过（1）」：内容先审后发，
 * 新内容一律先进待审队列。反过来（默认已通过）会让「漏审」变成「直接发布」，
 * 属于把安全默认值设在了危险的那一侧。
 *
 * 与 MemberStatusEnum 的差别同 TeamPositionEnum：
 * 没有框架注解引用它的字面量，故不配套常量类。
 *
 * @author shaoshing
 */
public enum AuditStatusEnum {

    /** 待审：仅作者本人可见，未对公众公开 */
    PENDING(0, "待审"),

    /** 已通过：公开可见 */
    APPROVED(1, "已通过"),

    /** 已驳回：作者可见并附带驳回理由，需修改后重新提交 */
    REJECTED(2, "已驳回");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    AuditStatusEnum(int value, String desc) {
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
     * 把数据库或前端传来的数字翻译成审核状态，查不到时返回 null
     *
     * <p>为什么这样设计：返回 null 而不是抛异常，是因为「不认识的值」在写路径和读路径上
     * 的正确处置完全相反。管理端按状态筛选时，传一个 9 进来必须立刻报参数错误——
     * 否则查询会正常返回空列表，管理员会以为「这个状态确实没有数据」，排查方向被彻底带偏。
     * 而在展示历史记录时，库里万一有脏值（例如手工改库写进了 9），
     * 只应把这一条显示成「未知状态」，不能让整个列表接口 500。
     * 让唯一知道上下文的调用方去决定，比在这里替它选一种行为安全得多。
     *
     * <p>为什么用遍历而不是 switch：新增状态（例如「已下架」）只要加一行常量，
     * 本方法不用改；switch 漏写 case 时编译器不会报错（普通 switch 语句对枚举没有穷尽性检查），
     * 表现就是新状态永远查成 null。常量只有三个，遍历的开销可以忽略。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：前端不传参数、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null，把「报错还是降级」交回调用方。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code status.value == value} 能正确比较，是因为左边是 int、
     * 右边是 Integer，比较前右边会自动拆箱成数值。若把字段也改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有 -128~127 的缓存值碰巧相等，
     * 会出现「明明有这个状态却查不到」的怪现象。
     *
     * @param value 待翻译的取值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或取值不在 0~2 之间时返回 null，调用方必须判空
     */
    public static AuditStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (AuditStatusEnum status : values()) {
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
     * <p>为什么这样设计：管理端列表的 status 是 GET 参数，不做注解校验，
     * 非法值只能在服务里挡住。如果提示只写「审核状态不合法」，调用方还得回来翻代码；
     * 把「0（待审）/ 1（已通过）/ 2（已驳回）」直接列出来，一次就能改对。
     * 文案由枚举生成，新增状态时提示自动跟着变，不会出现提示与实际取值不同步的错位。
     *
     * <p>为什么带中文描述：单独的数字没有语义，看到「2」并不知道是「已驳回」还是
     * 「已下架」；带上描述后，报错信息本身就是一份取值字典。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>每个元素拼成「取值（中文描述）」。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——分隔符与括号
     * 属于展示格式，随时可能为了可读性调整，不是稳定的协议。
     *
     * @return 形如「0（待审）/ 1（已通过）/ 2（已驳回）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AuditStatusEnum status : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：单独的数字没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

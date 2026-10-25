package com.bhu.runshistudioweb.model.enums;

/**
 * 成员状态枚举（studio_member.member_status）
 *
 * 为什么用 tinyint 而不是 varchar：
 * 它是纯状态位，取值只有「在读/在队」与「毕业/离队」两种、极少扩展，
 * 且官网需要按它做数值筛选（免费查询新增接口 ?memberStatus=0）。
 *
 * 取值必须与 db/user.sql 中 member_status 的列注释保持一致：
 * 0-在读/在队, 1-毕业/离队。
 *
 * 与 UserRoleEnum 的差别同 TeamPositionEnum：
 * 没有框架注解引用它的字面量，故不配套常量类。
 *
 * @author shaoshing
 */
public enum MemberStatusEnum {

    /** 在读/在队：官网成员页默认展示的群体 */
    IN_TEAM(0, "在读/在队"),

    /** 毕业/离队：官网「已毕业成员」板块使用 */
    GRADUATED(1, "毕业/离队");

    /** 入库的值（与 DDL 中 member_status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    MemberStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 member_status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的数字翻译成成员状态，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为这个值同时出现在
     * 查询条件和展示字段里，两者的正确处置相反。作为查询参数（官网的
     * {@code ?memberStatus=0}）时，传一个 5 进来必须报参数错误——否则会正常返回
     * 空列表，前端会以为「确实没有这类成员」，而不是「参数写错了」。
     * 作为展示字段时，一条历史脏数据只该让这一格显示成「未知」，
     * 不能让整个成员列表接口 500。把选择权留给知道上下文的调用方。
     *
     * <p>为什么用遍历而不是 switch：新增状态（例如「休学」）只要加一行常量，
     * 本方法一个字都不用改；switch 漏写 case 不会编译报错，只会静默查成 null。
     * 常量只有两个，遍历的开销完全可以忽略。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：GET 参数不传、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code status.value == value} 之所以正确，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱。若字段也改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，会出现「0 查得到、1 查不到」这类
     * 取决于缓存范围（-128~127）的怪现象。
     *
     * @param value 待翻译的取值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或取值不是 0 / 1 时返回 null，调用方必须判空
     */
    public static MemberStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (MemberStatusEnum status : values()) {
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
     * <p>为什么这样设计：成员状态是数字，前端传错时如果只提示
     * 「成员状态不合法」，调用方完全不知道 0 和 1 分别代表什么，
     * 只能去翻代码。把「0（在读/在队）/ 1（毕业/离队）」直接列出来，
     * 一次就能改对。文案由枚举生成，新增状态时提示自动跟着变。
     *
     * <p>为什么必须带中文描述：这是数字枚举，值本身没有任何语义，
     * 只拼「0 / 1」对使用者毫无帮助——他既不知道哪个是「在读」，
     * 也不知道该传哪一个。
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
     * @return 形如「0（在读/在队）/ 1（毕业/离队）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (MemberStatusEnum status : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字本身没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}
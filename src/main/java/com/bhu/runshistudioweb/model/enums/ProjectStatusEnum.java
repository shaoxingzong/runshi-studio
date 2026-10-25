package com.bhu.runshistudioweb.model.enums;

/**
 * 项目状态枚举（studio_project.status，tinyint）
 *
 * 为什么用 tinyint 而不是 varchar：
 * 它是纯状态位——取值固定为三个、几乎不会扩展，且官网要按它做数值筛选
 * （「只看已上线的项目」 ?status=1）。数字存 + 枚举约束，既省空间也便于精确匹配。
 *
 * 取值必须与 db/user.sql 中 status 的列注释保持一致：
 * 0-研发中, 1-已上线, 2-已结题，且 DDL 的默认值是 1。
 * 因此「新增项目不传 status」时的兜底也必须取 ONLINE，不能随便挑一个——
 * 否则同一份代码会出现「DB 默认 1、Service 默认 0」两套口径。
 *
 * 与 TeamPositionEnum 一样：没有框架注解引用它的字面量，故不配套常量类。
 *
 * @author shaoshing
 */
public enum ProjectStatusEnum {

    /** 研发中：还没上线，官网一般只在后台可见 */
    DEVELOPING(0, "研发中"),

    /** 已上线：官网默认展示的群体（DDL 默认值 1） */
    ONLINE(1, "已上线"),

    /** 已结题：历史项目，保留展示但不再维护 */
    ARCHIVED(2, "已结题");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ProjectStatusEnum(int value, String desc) {
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
     * 把数据库或前端传来的数字翻译成项目状态，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为同一个值在
     * 写路径与读路径上的正确处置相反。新增或修改项目时，状态写错必须报参数错误，
     * 否则一个 9 会静默入库，官网按状态筛选时这条项目就再也查不出来；
     * 而展示历史项目时，库里若存在手工改库留下的脏值，只该让这一格显示成
     * 「未知状态」，不能让整个项目列表 500。让知道上下文的调用方去决定。
     *
     * <p>为什么用遍历而不是 switch：新增状态（例如「已归档」细分）只要加一行常量，
     * 本方法一个字都不用改；switch 漏写 case 不会编译报错，只会静默查成 null。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：GET 参数不传、数据库列为 NULL 都会以 null 进来，
     *     不先判断就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法查不到时返回 null，而 DDL 的默认值是 1（已上线）。
     * 因此「新增项目不传 status」的兜底必须显式写成 {@code ONLINE.getValue()}，
     * 不能写成「of 查不到就用 0」——那会让同一份代码出现「库里默认 1、代码默认 0」
     * 两套口径，同一个请求走不同分支得到不同状态。
     *
     * @param value 待翻译的取值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或取值不在 0~2 之间时返回 null，调用方必须判空
     */
    public static ProjectStatusEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增状态时这里不用改
        for (ProjectStatusEnum status : values()) {
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
     * <p>为什么这样设计：状态是数字，前端传错时若只提示「项目状态不合法」，
     * 调用方既不知道 0/1/2 各代表什么，也不知道该传哪个。把
     * 「0（研发中）/ 1（已上线）/ 2（已结题）」直接列出来，一次就能改对。
     * 文案由枚举生成，新增状态时提示自动跟着变，不会出现提示与实际取值脱节。
     *
     * <p>为什么必须带中文描述：数字本身没有语义，只拼「0 / 1 / 2」等于什么都没说。
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
     * @return 形如「0（研发中）/ 1（已上线）/ 2（已结题）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ProjectStatusEnum status : values()) {
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

package com.bhu.runshistudioweb.model.enums;

/**
 * 错题消解方式（studio_quiz_wrong.resolve_type）
 *
 * 「消解」= 这条错题不用再复习：连对达标（自动）或用户手动标记。
 * 两种途径可信度不同——自动消解意味着确实做对了，手动只代表他觉得自己会了；
 * 记成同一个值，掌握度评估与推荐就再也分不出这两种情况。
 *
 * 坑：未消解时这一列必须是 NULL 而不是 0，否则所有判空处都要多考虑一种无信息的情况。
 *
 * @author shaoshing
 */
public enum QuizResolveTypeEnum {

    /** 自动：连续答对达到 studio.quiz.resolve-streak 次 */
    AUTO(1, "连对达标"),

    /** 手动：用户点「标记已掌握」 */
    MANUAL(2, "手动标记");

    /** 入库的值：1 或 2；未消解时不写这一列 */
    private final int value;

    /** 中文描述：日志与前端标签用；未消解时调用方拿到 null，拼文案前先判空 */
    private final String desc;

    /** 构造器包级私有：枚举实例只由 JVM 创建，switch 与 == 比较依赖「同值即同一实例」 */
    QuizResolveTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 取入库用的数字值；用 int 而非 Integer，枚举常量一定非空，调用方免去拆箱判空 */
    public int getValue() {
        return value;
    }

    /** 取中文描述：只用于日志与提示，需要分支时请比较枚举常量本身 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把消解方式翻译成枚举，查不到返回 null，不兜默认值
     *
     * 这一列本来就可空（未消解为 NULL），抛异常会让「读一条还没消解的错题」变成 500；
     * 兜成 AUTO 等于凭空编造「用户确实做对了」，掌握度统计被静默污染。
     *
     * @param value 数据库中的 resolve_type 值，允许为 null
     * @return 命中的枚举；null 或不在 1 / 2 内时返回 null
     */
    public static QuizResolveTypeEnum of(Integer value) {
        // ① 先挡 null：未消解时这一列就是 NULL，必须原样返回而不是拆箱
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增消解方式时这里不用改
        for (QuizResolveTypeEnum type : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (type.value == value) {
                return type;
            }
        }
        // ③ 脏值返回 null：读接口据此显示「未知」，不让整页失败
        return null;
    }
}

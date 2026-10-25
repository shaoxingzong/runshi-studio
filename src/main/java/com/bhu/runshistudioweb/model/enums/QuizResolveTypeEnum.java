package com.bhu.runshistudioweb.model.enums;

/**
 * 错题消解方式（对应 studio_quiz_wrong.resolve_type）
 *
 * <p>这里的「消解」指一条错题不再需要复习：要么连续答对达到阈值，要么用户自己点了「标记已掌握」。
 * 两种途径的可信度并不相同——自动消解意味着用户确实做对了，手动标记只代表他觉得自己会了。
 * 若把它们记成同一个值，将来做掌握度评估或错题推荐时就再也分不出这两种情况；而这一列只占一个 tinyint，
 * 省下它换不来任何收益，丢掉它却是永久的损失。
 *
 * <p>取值必须与 DDL 中 resolve_type 的列注释一致：1-连对达标（自动），2-手动标记。
 * 两处同源：列注释是给人看的取值字典，本枚举是给代码用的类型约束，改一处必须同步改另一处。
 *
 * <p>它描述的是「已消解」的成因，因此未消解的条目这一列必须是 NULL 而不是 0：
 * 一旦给 0 赋予「还没有任何方式」的含义，所有判空的地方都要多考虑一种情况，而它并不带来任何新信息。
 *
 * <p>没有框架注解引用本枚举的字面量，因此不配套常量类——枚举自身就是唯一的取值来源。
 *
 * @author shaoshing
 */
public enum QuizResolveTypeEnum {

    /** 自动：连续答对达到 studio.quiz.resolve-streak 次 */
    AUTO(1, "连对达标"),

    /** 手动：用户点「标记已掌握」 */
    MANUAL(2, "手动标记");

    /**
     * 入库的值（与 DDL 中 resolve_type 列取值一致）：1 或 2；未消解时不写这一列
     *
     * <p>用 int 而不是 String：它不对外展示、也不需要被前端按字面量分支，数字存法省空间且比较更快。
     */
    private final int value;

    /**
     * 中文描述，用于日志与前端标签
     *
     * <p>只在「已消解」的展示场景里出现；未消解时调用方拿到的是 null，拼文案前要先判空。
     */
    private final String desc;

    /**
     * 构造一个消解方式常量
     *
     * <p>构造器刻意声明为包级私有（不写 public）：枚举常量由 JVM 在类初始化阶段自己调用它，
     * 外部代码不需要也不应该自行构造枚举实例，否则「同值即同一实例」这个前提就没了，
     * 而 switch 与 == 比较都依赖这个前提。
     */
    QuizResolveTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 取入库用的数字值
     *
     * <p>返回值用 int 而不是 Integer：列本身允许 NULL，但枚举常量一定对应一个非空取值，
     * 用基本类型可以让调用方在拆箱时不必担心空指针。
     */
    public int getValue() {
        return value;
    }

    /**
     * 取中文描述
     *
     * <p>它只用于日志与提示文案，不参与任何比较或分支判断——需要分支时请比较枚举常量本身，不要比较文案，
     * 否则改一次措辞就可能悄悄改掉一段逻辑。
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库里的消解方式翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：这一列本来就可空——未消解的错题这一列是 NULL。
     * 若查不到就抛异常，「读取一条还没消解的错题」这种完全正常的操作就会变成 500。
     * 更重要的是它只出现在读路径上：库里若存在历史脏值（例如手工改库写进了 9），
     * 读接口应当把这一个字段降级展示，而不是让整张错题列表报错。
     * 真正需要拦住非法输入的写路径，应当在 Service 里先校验，不能指望这个查表方法替它把关。
     *
     * <p>为什么不兜一个默认值：把「认不出的值」兜成 AUTO（连对达标），
     * 等于凭空给这条错题编造了一个「用户确实做对了」的结论，
     * 掌握度统计会被静默污染，而且事后完全查不出来。宁可返回 null 让调用方显示「未知」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：未消解的条目这一列就是 NULL，必须原样返回 null，
     *     不能往下走去拆箱比较；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null（脏值场景）。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code type.value == value} 能正确比较，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱；若字段改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有缓存范围内的值碰巧相等。
     *
     * @param value 数据库中的 resolve_type 值，允许为 null；合法取值为 1 / 2
     * @return 命中的枚举常量；传 null 或取值不在 1 / 2 之内时返回 null，调用方必须判空
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

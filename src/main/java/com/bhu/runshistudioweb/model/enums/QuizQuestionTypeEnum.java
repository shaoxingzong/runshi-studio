package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题题型（闭集，只有选择题）
 *
 * 为什么没有「判断」：判断题等价于一道选项为「正确 / 错误」的单选，
 * 用 SINGLE 就能表达。单独设一个类型只会多一条判分分支、
 * 一套交互与一个导入标记，而表达能力没有任何提升。
 *
 * 取值必须与 db/user.sql 中 studio_quiz_question.type
 * 的列注释保持一致：0-单选, 1-多选。
 *
 * 两种题型的约束不同，且都由应用层校验（不写进 DDL）：
 * - 单选：恰好 1 个正确项，提交时恰好 1 个标签；
 * - 多选：至少 2 个正确项，提交时至少 1 个标签，全对才得分（不给部分分）。
 * 这些规则将来可能放宽（例如允许「2 选 1」的多选变体），写进 DDL 就得改表。
 *
 * @author shaoshing
 */
public enum QuizQuestionTypeEnum {

    /** 单选：恰好 1 个正确项 */
    SINGLE(0, "单选"),

    /** 多选：至少 2 个正确项，漏选/多选/错选均判错 */
    MULTIPLE(1, "多选");

    /** 入库的值（与 DDL 中 type 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizQuestionTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 type 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或导入文件里的题型数字翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：题型决定判分规则（单选要求恰好 1 个正确项，
     * 多选要求至少 2 个且全对才得分），认错题型就会判错分。因此
     * 导入题目、新增题目这两条写路径必须拿返回值判空后立刻报错，
     * 绝不能让一个 2 静默入库——那题以后每次作答都判分异常。
     * 而回显历史题目时，脏值只该让这一格显示成「未知题型」，
     * 不该让整页题库打不开。查不到返回 null，把选择权交给调用方。
     *
     * <p>为什么用遍历而不是 switch：新增题型（例如将来放宽的「不定项」）
     * 只要加一行常量，本方法一个字都不用改；switch 漏写 case 不会编译报错，
     * 只会静默查成 null。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：Excel 导入时该列可能为空，null 必须原样返回；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：{@code type.value == value} 能正确比较，是因为左边 int、
     * 右边 Integer，比较前右边会自动拆箱；若字段也改成 Integer，
     * 同一个 {@code ==} 就变成比对象引用，只有缓存范围内的值碰巧相等。
     *
     * @param value 待翻译的题型值，允许为 null；合法取值为 0 / 1
     * @return 命中的枚举常量；传 null 或不是 0 / 1 时返回 null，调用方必须判空
     */
    public static QuizQuestionTypeEnum of(Integer value) {
        // ① 先挡 null：导入文件的题型列可能为空，null 是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增题型时这里不用改
        for (QuizQuestionTypeEnum type : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (type.value == value) {
                return type;
            }
        }
        // ③ 查不到返回 null：写路径据此拒绝导入，而不是猜一个题型
        return null;
    }

    /**
     * 把所有合法题型拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：题型是数字且直接决定判分规则，导入时写错必须明确报错，
     * 并告诉使用者合法取值。把「0（单选）/ 1（多选）」列出来，
     * 整理 Excel 的人一次就能改对；文案由枚举生成，新增题型时提示自动跟着变。
     *
     * <p>为什么带中文描述：数字本身没有语义，只拼「0 / 1」无法让人判断
     * 哪个是单选、哪个是多选，等于没给提示。
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
     * @return 形如「0（单选）/ 1（多选）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizQuestionTypeEnum type : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字本身没有语义
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

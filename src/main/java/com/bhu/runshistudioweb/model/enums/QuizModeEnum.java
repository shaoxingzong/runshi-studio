package com.bhu.runshistudioweb.model.enums;

/**
 * 刷题模式（对应前端的三张模式卡）
 *
 * 它不落库：批次只在 studio_quiz_answer.batch_id 上留下痕迹，
 * 模式本身由前端持有（题目不含答案，整批交给前端即可）。
 * 因此本枚举只服务于「开批时决定选题策略」，没有对应的 DDL 列。
 *
 * 三种模式的职责必须不重叠，否则用户会在两个入口看到同一批题：
 * - SEQUENTIAL：从本章第一题往后走，跳过已作答（对或错）的题。
 * 答错的题不会在这里反复出现——它们归错题重刷管；
 * - WRONG：只出待复习的错题，最久没复习的先来；
 * - RANDOM：范围内全部启用题里随机抽，一轮内不重复。
 *
 * @author shaoshing
 */
public enum QuizModeEnum {

    /** 顺序刷题：按教材章节逐题，排除已作答的 */
    SEQUENTIAL(0, "顺序刷题"),

    /** 错题重刷：只刷错题本里待复习的题 */
    WRONG(1, "错题重刷"),

    /** 随机刷题：范围内随机抽题 */
    RANDOM(2, "随机刷题");

    /** 与前端约定的取值（前端 QuizMode 类型） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    QuizModeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 与前端约定的取值 */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把前端传来的刷题模式翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：模式只从前端传入，是「开批选题策略」的开关。
     * 传一个 9 进来绝不能默默按「顺序刷题」处理——那会让用户以为自己选了
     * 错题重刷，实际却拿到一整章新题，而他不会意识到是参数错了。
     * 因此这里只负责翻译，查到 null 时由开批的接口立刻报参数错误，
     * 并在提示里用 {@link #valuesText()} 列出三种合法模式。
     *
     * <p>为什么不在这里直接抛异常：本枚举没有任何落库列，将来若要在
     * 管理端做「模式说明」之类的只读展示，同样需要这个翻译方法；
     * 抛异常会让那种读场景也得包一层 try-catch。翻译与处置分开，复用面更大。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：不传模式时以 null 进来，不判空就拆箱比较会 NPE；</li>
     *     <li>遍历全部常量逐个比对，命中即返回；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要用「ordinal()」代替 value 来比对。ordinal 是常量声明顺序，
     * 一旦有人调整枚举常量的先后（看起来只是排版），线上前端传来的数字含义就全变了，
     * 而这种改动不会引起任何编译错误。value 是显式声明的业务取值，必须认它。
     *
     * @param value 前端传来的模式值，允许为 null；合法取值为 0 / 1 / 2
     * @return 命中的枚举常量；传 null 或不是 0 / 1 / 2 时返回 null，调用方必须判空
     */
    public static QuizModeEnum of(Integer value) {
        // ① 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是正常输入
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：按声明的 value 比对，不用 ordinal
        for (QuizModeEnum mode : values()) {
            // 左边 int、右边 Integer，比较的是数值而非对象引用
            if (mode.value == value) {
                return mode;
            }
        }
        // ③ 查不到返回 null：开批接口据此报「模式取值非法」
        return null;
    }

    /**
     * 把所有合法模式拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：前端的三张模式卡对应三个数字，传错时如果只提示
     * 「刷题模式不合法」，调用方无从判断 0/1/2 分别是什么。把
     * 「0（顺序刷题）/ 1（错题重刷）/ 2（随机刷题）」列出来，一次就能改对；
     * 而且这段文案由枚举生成，将来加一种模式时提示自动跟着变。
     *
     * <p>为什么带中文描述：这是数字枚举，值本身没有语义；
     * 只拼「0 / 1 / 2」对使用者毫无帮助。
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
     * @return 形如「0（顺序刷题）/ 1（错题重刷）/ 2（随机刷题）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (QuizModeEnum mode : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：数字本身没有语义
            text.append(mode.value).append("（").append(mode.desc).append("）");
        }
        return text.toString();
    }
}

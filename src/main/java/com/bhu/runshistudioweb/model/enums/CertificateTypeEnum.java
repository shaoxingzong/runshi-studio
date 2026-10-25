package com.bhu.runshistudioweb.model.enums;

/**
 * 证书「类型」维度枚举（对应 studio_certificate.award_type）
 *
 * 与 CertificateLevelEnum 是正交的两个维度：级别回答「含金量多高」，
 * 类型回答「这是什么成果」。两者组合起来才能完整描述一条记录，例如
 * 「国家级 + 软著」「省级 + 竞赛」。
 *
 * 为什么用 varchar 而不是 tinyint 存类型：
 * - 取值会扩展——以后可能加「实用新型」「外观设计」「会议论文」等，
 * 用 varchar 只需加枚举值，不必改表结构，也不用担心数字含义失忆；
 * - 这些值要直接参与接口返回与筛选，排障时能一眼读懂。
 * 反过来，user_status、member_status 这类「取值固定、不对外展示」的状态位才用 tinyint。
 *
 * @author shaoshing
 */
public enum CertificateTypeEnum {

    /** 学科竞赛 */
    COMPETITION("competition", "学科竞赛"),

    /** 软件著作权 */
    SOFT_COPYRIGHT("soft_copyright", "软件著作权"),

    /** 专利 */
    PATENT("patent", "专利"),

    /** 论文 */
    PAPER("paper", "论文");

    /** 入库的值（与 DDL 中的 award_type 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    CertificateTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 award_type 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的类型字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为写路径与读路径
     * 对「不认识的值」要求相反。新增或修改证书时，类型一旦写了枚举外的值，
     * 按类型筛选（competition / patent……）就会静默漏掉这条记录，用户只会看到
     * 「我明明录了却查不到」，所以写路径拿到 null 要立刻报参数错误。
     * 而读历史记录时，库里若存在手工改库留下的脏值，只应把这一个字段显示成
     * 「未知类型」，不能让整页列表 500——展示场景的可用性优先于单字段的准确性。
     * 把选择权留给唯一知道上下文的调用方，比在这里替它决定更安全。
     *
     * <p>为什么用遍历而不是 switch：新增类型（例如「实用新型」「会议论文」）
     * 只要加一行常量，本方法一个字都不用改；switch 漏写 case 不会编译报错，
     * 只会静默查成 null，而这种 bug 只有真遇到那个新值才会暴露。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较——常量在前，
     *     入参为 null 时返回 false 而不是抛 NPE；</li>
     *     <li>命中即返回，走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，且下划线不能省——"softcopyright"
     * 与 "soft_copyright" 是两回事。这类拼写错误不会报错，只会查不到，
     * 因此写路径必须拿 of 的返回值判空后再入库，不能直接信任前端传的字符串。
     *
     * @param value 待翻译的类型值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或不是四个合法取值之一时返回 null，调用方必须判空
     */
    public static CertificateTypeEnum of(String value) {
        // ① 遍历全部常量：新增类型时这里不用改
        for (CertificateTypeEnum type : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (type.value.equals(value)) {
                return type;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 按取值查找类型，查不到时退回调用方指定的兜底类型
     *
     * <p>为什么这样设计：它与 {@link #of(String)} 的差别只在「非法值不报错」。
     * 读历史数据、渲染已有记录时用它：某个字段写错了只该影响这一格展示，
     * 不该让整页报错。写路径绝不能用它——把非法值静默改成兜底类型再入库，
     * 等于替用户篡改了数据，而且不留任何痕迹，事后无法判断这条记录原本填了什么。
     *
     * <p>为什么不在这里写死默认类型：默认值属于业务判断，门户展示与统计报表
     * 的合理兜底并不相同；写死在枚举里就等于替所有调用方做了同一个决定，
     * 而它们本可以各自决定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先复用 {@link #of(String)} 查一次，保证判定规则只有一份；</li>
     *     <li>查到就返回它，查不到返回入参给的兜底类型。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「永不为 null」的前提是 defaultType 非空。
     * 若调用方传 null，这里会原样返回 null，后面拆箱照样 NPE，
     * 且栈里看不出是这里漏了兜底——默认值必须显式传枚举常量，不要传可能为空的变量。
     *
     * @param value 待翻译的类型值，允许为 null
     * @param defaultType 查不到时使用的类型，必须非空（建议直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultType；只有 defaultType 为 null 时才返回 null
     */
    public static CertificateTypeEnum ofOrDefault(String value, CertificateTypeEnum defaultType) {
        // ① 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        CertificateTypeEnum type = of(value);
        // ② 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return type == null ? defaultType : type;
    }

    /**
     * 把所有合法类型拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：非法类型的报错要一次说清合法取值，
     * 否则前端只能靠猜或翻代码。文案由枚举生成而不是手写，是为了让提示与取值同源：
     * 将来加一个类型，提示自动跟着变，不会出现「提示说支持四种、实际只认三种」的错位。
     *
     * <p>为什么只拼取值、不拼中文描述：类型值是英文单词，拼成
     * 「仅支持 competition / soft_copyright / patent / paper」已经可读；
     * 数值型枚举才必须带描述，因为数字本身没有语义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>只拼 value，不拼 desc。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——
     * 分隔符属于展示格式，不是稳定的协议。
     *
     * @return 形如「competition / soft_copyright / patent / paper」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateTypeEnum type : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 只拼取值：类型值是英文单词，本身已经可读
            text.append(type.value);
        }
        return text.toString();
    }
}

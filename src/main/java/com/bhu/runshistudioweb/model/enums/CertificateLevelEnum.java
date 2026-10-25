package com.bhu.runshistudioweb.model.enums;

/**
 * 证书「级别」维度枚举（对应 studio_certificate.award_level）
 *
 * 为什么级别和类型是两套枚举：若把「国家级竞赛 / 软著」这种
 * 「级别 + 类型」合并进一个字段，结果是任一维度都无法独立筛选与统计——
 * 想查「所有国家级证书」要写 LIKE '%国家级%'，既走不了索引，
 * 又会误匹配到「国家级软著」与「国家级竞赛」之外的写法；想统计「有多少竞赛」同样无解。
 * 拆成两个正交字段后：
 * - 按级别筛选 → 走 idx_level_date (award_level, award_date DESC)；
 * - 按类型筛选 → 走 idx_type_date (award_type, award_date DESC)。
 * 两个维度各自独立，也能自由组合（国家级 + 软著、省级 + 竞赛……）。
 *
 * 维护约定：新增级别时要同步改 DDL 的列注释（DDL 注释即取值字典），
 * 否则会出现「枚举里加了、库里没写」，后人按注释写代码就会漏值。
 *
 * @author shaoshing
 */
public enum CertificateLevelEnum {

    /** 国家级 */
    NATIONAL("national", "国家级"),

    /** 省级 */
    PROVINCIAL("provincial", "省级"),

    /** 市级 / 校级 */
    MUNICIPAL("municipal", "市级/校级");

    /** 入库的值（与 DDL 中的 award_level 列取值一致，大小写敏感） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    CertificateLevelEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 award_level 列取值一致，大小写敏感） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的级别字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：查不到返回 null 而不是抛异常，是因为写路径与读路径的处置相反。
     * 新增或修改证书时，级别必须落在 national / provincial / municipal 之内，
     * 非法值一旦入库，官网按级别筛选就会静默漏数据（筛选条件永远匹配不上它），
     * 所以写路径拿到 null 要立刻报参数错误，并在提示里列出合法取值。
     * 而读路径展示历史记录时，库里若存在早期写法或手工改库留下的脏值，
     * 只应把这一条显示成「未知级别」，不能让整页列表报错。
     * 让调用方决定，比在这里替它选一种行为安全——它才知道当前是写还是读。
     *
     * <p>为什么用遍历而不是 switch：新增级别（例如「区级」）只要加一行常量，
     * 本方法一个字都不用改；switch 漏写 case 不会编译报错，只会静默查成 null。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较——常量在前，
     *     入参为 null 时返回 false 而不是抛 NPE；</li>
     *     <li>命中即返回，走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，"National" 与 "national" 不是同一个级别。
     * 这里刻意不做忽略大小写的兜底：大小写由写路径决定，读路径擅自放宽会让
     * 「写错了」永远暴露不出来；真要放宽，应当改在写路径统一转小写，而不是读路径另立规则。
     *
     * @param value 待翻译的级别值，允许为 null；合法取值见 {@link #valuesText()}
     * @return 命中的枚举常量；传 null 或不是三个合法取值之一时返回 null，调用方必须判空
     */
    public static CertificateLevelEnum of(String value) {
        // ① 遍历全部常量：新增级别时这里不用改
        for (CertificateLevelEnum level : values()) {
            // ② 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (level.value.equals(value)) {
                return level;
            }
        }
        // 查不到返回 null：调用方才知道该报错还是该降级
        return null;
    }

    /**
     * 按取值查找级别，查不到时退回调用方指定的兜底级别
     *
     * <p>为什么这样设计：它与 {@link #of(String)} 的唯一差别是「非法值不报错」。
     * 读历史数据、展示已有记录时必须用这个版本：一条证书的级别字段哪怕写错了，
     * 也只该让这一个字段显示成兜底值，不能让整页 500——展示场景的可用性
     * 优先于单个字段的准确性。反过来，写路径绝不能用它：把非法值静默改成
     * 「市级/校级」再入库，等于替用户改了数据，而且没有任何提示，
     * 事后完全查不出这条记录原本填的是什么。
     *
     * <p>为什么不在这里写死一个默认级别：默认值属于业务判断，不同调用方口径不同
     * （门户展示可以兜「市级/校级」，统计报表可能兜「未知」），
     * 写死在枚举里就等于替所有调用方做了同一个决定。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先复用 {@link #of(String)} 查一次，保证判定规则只有一份；</li>
     *     <li>查到就返回它，查不到返回入参给的兜底级别。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法「永不为 null」的前提是 defaultLevel 非空。
     * 若调用方传了 null，这里会原样返回 null，后面的拆箱或链式调用照样 NPE，
     * 而且栈里看不出是这里漏了兜底——所以默认值必须由调用方用常量显式给出，
     * 不能传一个从别处读来的、可能为空的变量。
     *
     * @param value 待翻译的级别值，允许为 null
     * @param defaultLevel 查不到时使用的级别，必须非空（建议直接传枚举常量）
     * @return 命中的枚举，或传入的 defaultLevel；只有 defaultLevel 为 null 时才返回 null
     */
    public static CertificateLevelEnum ofOrDefault(String value, CertificateLevelEnum defaultLevel) {
        // ① 复用 of 查一次：判定规则只有一份，不会出现两个方法口径不一致
        CertificateLevelEnum level = of(value);
        // ② 查不到才用兜底：合法值必须原样返回，不能被默认值覆盖
        return level == null ? defaultLevel : level;
    }

    /**
     * 把所有合法级别拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：非法级别的报错必须告诉用户「哪些值才是对的」，
     * 否则前端只能去翻代码或试错。文案由枚举生成而不是手写，
     * 是为了让提示与取值永远同源：将来加一个级别，提示自动跟着变，
     * 不会出现「提示说支持三种、实际只认两种」的错位。
     *
     * <p>为什么这里只拼取值、不拼中文描述：级别值是英文单词，
     * 拼进「仅支持 national / provincial / municipal」已经足够可读；
     * 而数值型枚举（如审核状态 0/1/2）必须带上描述，否则数字本身没有语义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>逐个追加，只在已有内容时补分隔符，避免末尾多出一个「 / 」；</li>
     *     <li>只拼 value，不拼 desc。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回值只用于展示，不要拿它反解析出枚举——
     * 分隔符属于展示格式，随时可能调整，不是稳定的协议。
     *
     * @return 形如「national / provincial / municipal」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (CertificateLevelEnum level : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 只拼取值：级别值是英文单词，本身已经可读
            text.append(level.value);
        }
        return text.toString();
    }
}

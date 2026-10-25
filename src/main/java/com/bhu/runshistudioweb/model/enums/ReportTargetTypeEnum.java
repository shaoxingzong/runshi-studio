package com.bhu.runshistudioweb.model.enums;

/**
 * 举报目标类型（studio_post_report.target_type）
 *
 * 为什么用 varchar 存类型而不是给帖子和评论各建一张举报表：
 * 两张举报表的字段几乎完全一样（举报人、理由、处理状态、处理人），
 * 拆开只是把同一套逻辑复制两份，还让「我的举报记录」这类跨类型查询变成两次查询 + 内存合并。
 * 用 target_type + target_id 的「软关联」是这里更合适的取舍
 * ——代价是数据库无法用外键保证 target_id 有效，
 * 因此删除帖子/评论时必须在应用层一并处置其举报记录（不能留下悬空举报）。
 *
 * 取值必须与 DDL 中 studio_post_report.target_type 的列注释保持一致：
 * post-帖子, comment-评论。
 *
 * @author shaoshing
 */
public enum ReportTargetTypeEnum {

    POST("post", "帖子"),

    COMMENT("comment", "评论");

    /** 入库的值（与 DDL 中 target_type 列取值一致） */
    private final String value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    ReportTargetTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 target_type 列取值一致） */
    public String getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库或前端传来的目标类型翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：target_type 与 target_id 是软关联（数据库没有外键约束），
     * 因此「这个 id 该去哪张表查」完全由本枚举决定。若这里抛异常，
     * 一条历史脏数据就会让整页举报记录打不开；若兜底成 POST，
     * 一条本该指向评论的举报会去查帖子表，可能查到一个同 id 的帖子——
     * 展示出完全无关的内容，而且不报错，这是最坏的一种结果。
     * 因此这里只做翻译，查不到返回 null，由调用方决定「跳过这一条」还是「报错」。
     *
     * <p>为什么额外挡空串：本列是 varchar，前端表单清空后可能提交 ""
     * 而不是 null。空串去遍历比对会白跑一轮且必然不命中，
     * 先挡掉能让「空值」这条最常见的分支更直白，也避免调用方误以为
     * 空串是一种合法类型。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null 与空串：两者都表示「没填」，直接返回 null；</li>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较（常量在前，免判空）；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：只挡了空串，没挡纯空格。" " 会走完遍历后返回 null，
     * 结果同样是 null，但调用方若在日志里打印入参，看到的是「一个空格」而不是
     * 「没传值」，排查时容易误判成前端传了脏数据。调用前先 trim 是更稳妥的做法。
     *
     * @param value 待翻译的类型值，允许为 null 或空串；合法取值为 post / comment
     * @return 命中的枚举常量；传 null、空串或未知类型时返回 null，调用方必须判空
     */
    public static ReportTargetTypeEnum of(String value) {
        // ① 先挡 null 与空串：两者都表示「没填」，没必要再遍历
        if (value == null || value.isEmpty()) {
            return null;
        }
        // ② 遍历全部常量：新增目标类型时这里不用改
        for (ReportTargetTypeEnum type : values()) {
            // 常量在前调用 equals：入参为 null 时返回 false，不会抛 NPE
            if (type.value.equals(value)) {
                return type;
            }
        }
        // ③ 查不到返回 null：调用方据此跳过或报错，绝不猜一个默认类型
        return null;
    }

    /**
     * 把所有合法目标类型拼成一段可以直接放进错误提示的文案
     *
     * <p>为什么这样设计：举报接口的 targetType 由前端传入，非法值必须明确拒绝，
     * 否则会写出一条「不知道指向哪里」的举报记录。拒绝时把
     * 「post（帖子）/ comment（评论）」列出来，调用方一次就能改对；
     * 文案由枚举生成，新增类型时提示自动跟着变。
     *
     * <p>为什么带中文描述：虽然取值是英文单词，但「post 指帖子、comment 指评论」
     * 这层对应对不熟悉本模块的人并不直观，带上描述后报错信息能自解释。
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
     * @return 形如「post（帖子）/ comment（评论）」的文案；枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (ReportTargetTypeEnum type : values()) {
            // ① 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // ② 值 + 中文描述一起拼：这层对应关系并不直观
            text.append(type.value).append("（").append(type.desc).append("）");
        }
        return text.toString();
    }
}

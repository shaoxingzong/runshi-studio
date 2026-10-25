package com.bhu.runshistudioweb.model.enums;

/**
 * 站内通知类型（studio_notification.type）
 *
 * 取值必须与 DDL 中 type 的列注释保持一致。
 * 用字符串而非数字：排障时一眼读懂、新增类型不必改表（与 user_role 同一思路）。
 *
 * 新增类型的检查清单（漏一步就是静默问题）：
 * - 本枚举加值（value + desc）；
 * - DDL 列注释补上（保持「枚举是唯一事实来源」的可核对性）；
 * - 在触发点调用 NotificationService#notify——没有触发点的类型不该存在；
 * - 前端通知页的跳转映射补对应分支（见 Notifications.vue 的 bizRoute）。
 *
 * @author shaoshing
 */
public enum NotificationTypeEnum {

    /** 帖子审核通过：作者收到「你的帖子已公开」 */
    POST_APPROVED("POST_APPROVED", "帖子审核通过"),

    /** 帖子被驳回：正文带驳回理由（作者知道该改哪里） */
    POST_REJECTED("POST_REJECTED", "帖子被驳回"),

    /** 评论被回复：指向原评论所在帖子 */
    COMMENT_REPLIED("COMMENT_REPLIED", "评论被回复"),

    /** 获得工作室成员身份：由管理员在用户管理里授予 */
    MEMBER_GRANTED("MEMBER_GRANTED", "获得成员身份");

    /** 入库的值（与 DDL 中 type 列取值一致） */
    private final String value;

    /** 中文描述，用于日志与前端标签 */
    private final String desc;

    NotificationTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * 取值（落库与前端分支依据）
     *
     * @return 取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 中文描述（日志与提示用）
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数据库里的通知类型字符串翻译成枚举，查不到时返回 null
     *
     * <p>为什么这样设计：这个方法只用在读路径（把通知记录转成前端要的 VO，
     * 由类型决定点击后跳哪里），所以它对「不认识的值」只有一个合理反应：
     * 返回 null 让调用方降级成「这条通知不跳转」。如果这里抛异常，
     * 一条历史脏数据就会让整个通知列表打不开——而通知列表恰恰是
     * 用户查看「我的内容出了什么事」的入口，最不该整页失败。
     *
     * <p>为什么不兜一个默认类型：兜底会把「类型写错了」伪装成
     * 「这是一条帖子审核通过通知」，用户点进去落到错误的页面，
     * 而真正的数据问题被永久藏住。宁可显示成不可点击，也不要跳到错的地方。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null：列为 NULL 或老数据缺这一列时会以 null 进来；</li>
     *     <li>遍历全部常量，用常量自己的 value 与入参比较（常量在前，免判空）；</li>
     *     <li>走完仍未命中返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：比较区分大小写，"post_approved" 与 "POST_APPROVED"
     * 查不到。这不是缺陷而是刻意的：类型值是写路径用枚举常量写进去的，
     * 读路径放宽大小写只会掩盖「某处手写了小写字符串」这类真正的 bug。
     *
     * @param value 数据库中的类型值，允许为 null；合法取值见类注释里的清单
     * @return 命中的枚举常量；传 null 或不是已知类型时返回 null，调用方必须判空
     */
    public static NotificationTypeEnum of(String value) {
        // ① 先挡 null：列为 NULL 时直接返回，避免下面 equals 的语义歧义
        if (value == null) {
            return null;
        }
        // ② 遍历全部常量：新增通知类型时这里不用改
        for (NotificationTypeEnum type : values()) {
            // 常量在前调用 equals：即使漏了上面的判空也不会抛 NPE
            if (type.value.equals(value)) {
                return type;
            }
        }
        // ③ 查不到返回 null：调用方降级成「不跳转」，而不是让列表报错
        return null;
    }
}

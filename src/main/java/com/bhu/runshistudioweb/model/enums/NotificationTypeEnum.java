package com.bhu.runshistudioweb.model.enums;

/**
 * 站内通知类型（studio_notification.type）
 *
 * 用字符串而非数字：排障一眼读懂、新增类型不必改表。取值与 DDL 列注释同源。
 *
 * 新增类型要同步四处，漏一步都是静默问题：本枚举、DDL 列注释、
 * 触发点的 NotificationService#notify、前端通知页的跳转映射。
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
    MEMBER_GRANTED("MEMBER_GRANTED", "获得成员身份"),

    /**
     * 举报已受理：内容被撤下或删除
     *
     * 必须给举报人反馈：举报最怕「提交后毫无音讯」，一次没有回应的举报会让用户
     * 再也不使用这个通道，问题内容也就失去了被发现的机会。
     */
    REPORT_RESOLVED("REPORT_RESOLVED", "举报已受理"),

    /** 举报未成立：内容保留，举报关闭 */
    REPORT_DISMISSED("REPORT_DISMISSED", "举报未成立");

    /** 入库的值（与 DDL 中 type 列取值一致） */
    private final String value;

    /** 中文描述，用于日志与前端标签 */
    private final String desc;

    NotificationTypeEnum(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 取值：落库与前端跳转分支的依据 */
    public String getValue() {
        return value;
    }

    /** 中文描述：日志与前端标签用 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把通知类型字符串翻译成枚举，查不到返回 null
     *
     * 只用在读路径：查不到就让这条通知降级成「不跳转」，不能让整个列表打不开。
     * 不兜默认类型：兜底会让用户点到错误的页面，真正的数据问题被永久藏住。
     *
     * @param value 数据库中的类型值，允许为 null
     * @return 命中的枚举；null 或未知类型时返回 null
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

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
     * 按取值查找类型
     *
     * @param value 数据库中的类型值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static NotificationTypeEnum of(String value) {
        if (value == null) {
            return null;
        }
        for (NotificationTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}

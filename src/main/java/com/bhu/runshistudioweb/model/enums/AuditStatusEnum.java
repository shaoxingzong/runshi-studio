package com.bhu.runshistudioweb.model.enums;

/**
 * 内容审核状态（**帖子与评论共用**）
 *
 * author: shaoshing
 *
 * <p>为什么帖子与评论共用一套枚举，而不是各建一个
 * （{@code PostStatusEnum} / {@code CommentStatusEnum}）：
 * 两者的状态机完全一致——「待审 → 已通过 / 已驳回」，取值语义也一模一样。
 * 建两个只会让将来加状态时改两处、还容易改漏一处。
 * 真正需要分开的是**各自的业务动作**（帖子能置顶、评论能级联删除），那是代码层面的差异，
 * 不是状态取值层面的差异。
 *
 * <p>取值必须与 DDL 中 {@code studio_post.status} 与 {@code studio_post_comment.status}
 * 的列注释保持一致：{@code 0-待审, 1-已通过, 2-已驳回}。
 *
 * <p><b>默认态必须是「待审（0）」而不是「已通过（1）」</b>：内容先审后发，
 * 新内容一律先进待审队列。反过来（默认已通过）会让「漏审」变成「直接发布」，
 * 属于把安全默认值设在了危险的那一侧。
 *
 * <p>与 {@link MemberStatusEnum} 的差别同 {@link TeamPositionEnum}：
 * 没有框架注解引用它的字面量，故不配套常量类。
 */
public enum AuditStatusEnum {

    /** 待审：仅作者本人可见，未对公众公开 */
    PENDING(0, "待审"),

    /** 已通过：公开可见 */
    APPROVED(1, "已通过"),

    /** 已驳回：作者可见并附带驳回理由，需修改后重新提交 */
    REJECTED(2, "已驳回");

    /** 入库的值（与 DDL 中 status 列取值一致） */
    private final int value;

    /** 中文描述，用于日志与提示 */
    private final String desc;

    AuditStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于日志与提示 */
    public String getDesc() {
        return desc;
    }

    /**
     * 按取值查找状态
     *
     * @param value 数据库中的状态值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static AuditStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (AuditStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code 0（待审） / 1（已通过） / 2（已驳回）}
     *
     * @return 以「 / 」分隔的合法取值（含中文含义，因为数字单独看没有语义）
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AuditStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

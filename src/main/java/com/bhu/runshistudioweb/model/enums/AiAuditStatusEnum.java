package com.bhu.runshistudioweb.model.enums;

/**
 * AI 内容审核结论（studio_post.ai_audit_status / studio_post_comment.ai_audit_status）
 *
 * @author shaoshing
 */
public enum AiAuditStatusEnum {

    /** 未判：还没送审，或已提交但异步任务尚未执行 */
    NOT_JUDGED(0, "未判"),

    /** 安全：AI 确信没问题，已自动通过并公开 */
    SAFE(1, "安全（自动通过）"),

    /** 违规：AI 确信有问题，已自动驳回 */
    BLOCKED(2, "违规（自动驳回）"),

    /** 灰色：AI 拿不准，保持待审转人工 */
    REVIEW(3, "灰色（待人工）"),

    /** 判定失败：模型未配置 / 超时 / 报错 / 输出无法解析，保持待审转人工 */
    FAILED(4, "判定失败（待人工）");

    /** 入库的值（与 DDL 中 ai_audit_status 列取值一致） */
    private final int value;

    /** 中文描述，用于管理端展示与日志 */
    private final String desc;

    AiAuditStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** 入库的值（与 DDL 中的 ai_audit_status 列取值一致） */
    public int getValue() {
        return value;
    }

    /** 中文描述，用于管理端展示与日志 */
    public String getDesc() {
        return desc;
    }

    /**
     * 把数字翻译成审核结论，查不到返回 null，由调用方决定报错还是降级
     *
     * @param value 待翻译的取值，允许为 null
     * @return 命中的枚举；null 或不在 0~4 时返回 null
     */
    public static AiAuditStatusEnum of(Integer value) {
        // 先挡 null：不判空就拆箱比较会 NPE，而 null 在这里是完全正常的输入
        if (value == null) {
            return null;
        }
        // 遍历全部常量：新增结论时这里不用改，常量列表自己会变长
        for (AiAuditStatusEnum status : values()) {
            // 左边 int、右边 Integer，比较的是数值而不是对象引用
            if (status.value == value) {
                return status;
            }
        }
        // 查不到返回 null：报错还是降级，由调用方按场景决定
        return null;
    }

    /**
     * 拼出可直接放进错误提示的合法取值文案，如「0（未判）/ 1（安全（自动通过））」
     *
     * 数字本身没有语义，必须带中文描述；返回值只用于展示，不要反解析成枚举。
     *
     * @return 文案，枚举至少有一个常量，不会返回空串
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiAuditStatusEnum status : values()) {
            // 只有已经拼过内容才补分隔符：末尾不会多出一个「 / 」
            if (text.length() > 0) {
                text.append(" / ");
            }
            // 值 + 中文描述一起拼：数字单独看没有语义
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

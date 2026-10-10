package com.bhu.runshistudioweb.model.enums;

/**
 * AI 内容审核结论（<b>帖子与评论共用</b>）
 *
 * author: shaoshing
 *
 * <p>它记录的是「机器判了什么」，与 {@link AuditStatusEnum}（内容当前的实际状态）是<b>两列</b>，
 * 不可以互相替代：
 * <ul>
 *     <li>{@link AuditStatusEnum} 是<b>结果</b>：内容此刻是待审、已通过还是已驳回；</li>
 *     <li>本枚举是<b>来源与把握</b>：这个结果是机器判的、判得有多确定，还是根本没判成。</li>
 * </ul>
 * 例如「AI 判安全」会让 {@code status} 变成已通过，但审计时必须能说出
 * 「这条是机器放的、没人看过」——只留 {@code status} 一列就说不清这句话。
 *
 * <p><b>为什么「未判（0）」与「判定失败（4）」要分成两个值</b>：
 * 合成一个的话，管理端无法区分「还没轮到它」和「AI 挂了」。
 * 前者只是排队中，后者是需要运维介入的信号——把故障信号藏进「排队中」是最难发现的一类问题。
 *
 * <p><b>为什么要有「灰色（3）」这一档</b>：模型不是全知全能，它对一部分内容的判断本身就不确定。
 * 逼它在「放行」和「驳回」里二选一，等于把不确定性硬转成误判；
 * 留一档「我说不准，交给人」才是这套机制减负又不失守的关键（业界称「三分法」）。
 *
 * <p>取值必须与 DDL 中 {@code studio_post.ai_audit_status} 与
 * {@code studio_post_comment.ai_audit_status} 的列注释保持一致。
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
     * 按取值查找结论
     *
     * @param value 数据库中的状态值
     * @return 匹配的枚举；无匹配（含 null）时返回 null
     */
    public static AiAuditStatusEnum of(Integer value) {
        if (value == null) {
            return null;
        }
        for (AiAuditStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }

    /**
     * 所有合法取值的可读文案，形如 {@code 0（未判） / 1（安全） / ...}
     *
     * @return 以「 / 」分隔的合法取值（含中文含义，因为数字单独看没有语义）
     */
    public static String valuesText() {
        StringBuilder text = new StringBuilder();
        for (AiAuditStatusEnum status : values()) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(status.value).append("（").append(status.desc).append("）");
        }
        return text.toString();
    }
}

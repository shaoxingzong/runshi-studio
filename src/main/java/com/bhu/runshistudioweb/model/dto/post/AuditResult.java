package com.bhu.runshistudioweb.model.dto.post;

import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;

/**
 * AI 内容审核的结论（{@code ContentAuditManager} 的产出）
 *
 * author: shaoshing
 *
 * <p>它是<b>判定</b>与<b>落库</b>之间的那道边界：Manager 只产出结论，
 * 由 Service 决定把它写进哪张表、要不要顺带维护冗余列。
 * 这条边界的意义是<b>依赖单向</b>——Manager 不认识任何 Service，
 * 否则就会形成循环依赖（本项目已全量改为构造器注入，成环会启动即失败）。
 *
 * <p>{@code reason} 的两种用途不要混：
 * <ul>
 *     <li>判为违规时，它会被写进 {@code reject_reason} 给用户看；</li>
 *     <li>判为灰色或失败时，它写进 {@code ai_reason} 给管理员参考。</li>
 * </ul>
 */
public record AuditResult(AiAuditStatusEnum status, String reason) {

    /**
     * 紧凑构造器：结论不允许为 null
     *
     * <p>兜成 {@link AiAuditStatusEnum#REVIEW}（转人工）而不是抛异常——
     * 少一个结论不该让整次审核流程失败，而「没结论」最安全的解释就是「交给人看」。
     */
    public AuditResult {
        if (status == null) {
            status = AiAuditStatusEnum.REVIEW;
        }
    }

    /** 判为安全（将自动通过并公开） */
    public static AuditResult safe(String reason) {
        return new AuditResult(AiAuditStatusEnum.SAFE, reason);
    }

    /** 判为违规（将自动驳回） */
    public static AuditResult blocked(String reason) {
        return new AuditResult(AiAuditStatusEnum.BLOCKED, reason);
    }

    /** 拿不准，转人工 */
    public static AuditResult review(String reason) {
        return new AuditResult(AiAuditStatusEnum.REVIEW, reason);
    }

    /** 没判成（未启用 / 调用失败 / 输出无法解析），转人工 */
    public static AuditResult failed(String reason) {
        return new AuditResult(AiAuditStatusEnum.FAILED, reason);
    }
}

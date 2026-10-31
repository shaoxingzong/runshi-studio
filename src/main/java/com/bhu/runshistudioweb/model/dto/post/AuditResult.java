package com.bhu.runshistudioweb.model.dto.post;

import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;

/**
 * AI 内容审核的结论（ContentAuditManager 的产出）
 *
 * @author shaoshing
 */
public record AuditResult(AiAuditStatusEnum status, String reason) {

    public AuditResult {
        if (status == null) {
            status = AiAuditStatusEnum.REVIEW;
        }
    }

    /**
     * 产出一条「安全」结论：AI 确信内容没问题，调用方可以据此自动通过并公开
     *
     * @param reason 判定理由，可为 null（落库前会兜底成结论的描述）
     * @return 结论对象，status 恒为 SAFE，永不为 null
     */
    public static AuditResult safe(String reason) {
        return new AuditResult(AiAuditStatusEnum.SAFE, reason);
    }

    /**
     * 产出一条「违规」结论：AI 确信内容有问题，调用方可以据此自动驳回
     *
     * @param reason 驳回理由，可为 null；非 null 时直接展示给作者，文案要写成「作者看了知道该改哪里」
     * @return 结论对象，status 恒为 BLOCKED，永不为 null
     */
    public static AuditResult blocked(String reason) {
        return new AuditResult(AiAuditStatusEnum.BLOCKED, reason);
    }

    /**
     * 产出一条「灰色」结论：模型拿不准，内容保持待审、交给人看
     *
     * @param reason 模型给出的理由或「无法解析」的说明，可为 null，写进 ai_reason 供人工复核
     * @return 结论对象，status 恒为 REVIEW，永不为 null；调用方据此保持内容为待审
     */
    public static AuditResult review(String reason) {
        return new AuditResult(AiAuditStatusEnum.REVIEW, reason);
    }

    /**
     * 产出一条「判定失败」结论：这次根本没判成（未启用 / 调用失败 / 输出为空），转人工
     *
     * 判定失败时绝不能默认放行：没审过的内容不等于安全内容，降级方向只能是更保守。
     *
     * @param reason 失败原因，可为 null；建议写成管理员能据此行动的话，例如「AI 审核未启用，转人工」
     * @return 结论对象，status 恒为 FAILED，永不为 null；调用方据此保持内容为待审并记进 ai_reason
     */
    public static AuditResult failed(String reason) {
        return new AuditResult(AiAuditStatusEnum.FAILED, reason);
    }
}

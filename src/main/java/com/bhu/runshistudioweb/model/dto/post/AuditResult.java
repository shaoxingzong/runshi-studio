package com.bhu.runshistudioweb.model.dto.post;

import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;

/**
 * AI 内容审核的结论（ContentAuditManager 的产出）
 *
 * 它是判定与落库之间的边界：Manager 只产出结论，由 Service 决定写进哪张表、要不要维护冗余列。
 * 这条边界保证依赖单向——Manager 不认识任何 Service，否则会形成循环依赖（本项目全部构造器注入，成环会启动即失败）。
 *
 * reason 的两种用途不要混：判为违规时写进 reject_reason 给作者看；判为灰色或失败时写进 ai_reason 给管理员参考。
 *
 * @author shaoshing
 */
public record AuditResult(AiAuditStatusEnum status, String reason) {

    // 紧凑构造器：结论不允许为 null
    //
    // 兜成 REVIEW（转人工）而不是抛异常——少一个结论不该让整次审核流程失败，
    // 而「没结论」最安全的解释就是「交给人看」。
    public AuditResult {
        if (status == null) {
            status = AiAuditStatusEnum.REVIEW;
        }
    }

    /**
     * 产出一条「安全」结论：AI 确信内容没问题，调用方可以据此自动通过并公开
     *
     * 结论由这组工厂方法产出，而不是让调用方直接 new，这样「有哪些结论」收敛成四个有名字的入口，
     * 且结论与后续动作保持分离：本类只回答「机器判了什么」，「要不要改 status」由 Service 决定。
     * 安全结论的 reason 只会写进 ai_reason，不展示给作者，可以写得具体一点。
     *
     * 坑：safe 是四个工厂里唯一会让内容立刻公开的一个，调用点必须确认模型确实给出了明确的安全判断，
     * 而不是「解析失败」「服务不可用」——把「没判成」写成 safe 等于给所有审核故障开自动放行的后门。
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
     * 这一档的 reason 会被写进 reject_reason 直接展示给作者，与其余三档只进 ai_reason 不同。
     * 不做「两个 reason 参数」是因为模型一次只给一个理由，拆开会出现作者与管理员看到的理由不一致。
     *
     * 坑：不要因为「内容看着不舒服」就调本方法。BLOCKED 会让内容立刻变成已驳回并带着理由回到作者手里，
     * 模糊地带应当交给 review，让人工在管理端看一眼。
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
     * 这是三分法里最容易被删掉、却最不能删的一档：模型对一部分内容本来就不确定，
     * 逼它在「放行」与「驳回」里二选一，不确定性就会被硬转成误判。
     *
     * 本结论不改变审核状态，只把建议写进 ai_reason——「待审」本身就是正确状态；
     * 也不写 reject_reason：还没定性，不该给作者「你违规了」的暗示。
     *
     * 坑：解析失败该用 failed 还是 review，判据是「有没有拿到模型的有效输出」。
     * 输出为空、超时、服务不可用属于 failed（这次没判）；拿到了但不是约定的 JSON 属于 review。
     * 混成一个值会把「服务商故障」藏进「模型拿不准」里，故障永远查不出来。
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
     * 与 review 一样都不改审核状态，但语义完全不同：REVIEW 是「模型给出了判断，只是它自己也不确定」，
     * FAILED 是「模型没给出任何可用判断」。合成一个值会让管理端无法区分「这条需要重点看」
     * 与「AI 这一整段时间都挂了」，而后者是需要运维介入的信号。
     *
     * 判定失败时绝不能默认放行：没审过的内容不等于安全内容，降级方向只能是更保守。
     *
     * 坑：不要把本方法当成「懒得判」的兜底，否则管理端会堆满「判定失败」，
     * 运维照着这个信号去查 AI 服务，方向从一开始就错了。
     *
     * @param reason 失败原因，可为 null；建议写成管理员能据此行动的话，例如「AI 审核未启用，转人工」
     * @return 结论对象，status 恒为 FAILED，永不为 null；调用方据此保持内容为待审并记进 ai_reason
     */
    public static AuditResult failed(String reason) {
        return new AuditResult(AiAuditStatusEnum.FAILED, reason);
    }
}

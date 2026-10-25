package com.bhu.runshistudioweb.model.dto.post;

import com.bhu.runshistudioweb.model.enums.AiAuditStatusEnum;

/**
 * AI 内容审核的结论（ ContentAuditManager 的产出）
 *
 * 它是判定与落库之间的那道边界：Manager 只产出结论，
 * 由 Service 决定把它写进哪张表、要不要顺带维护冗余列。
 * 这条边界的意义是依赖单向——Manager 不认识任何 Service，
 * 否则就会形成循环依赖（本项目全部使用构造器注入，成环会启动即失败）。
 *
 * reason 的两种用途不要混：
 * - 判为违规时，它会被写进 reject_reason 给用户看；
 * - 判为灰色或失败时，它写进 ai_reason 给管理员参考。
 *
 * @author shaoshing
 */
public record AuditResult(AiAuditStatusEnum status, String reason) {

    // 紧凑构造器：结论不允许为 null
    //
    // 兜成 AiAuditStatusEnum#REVIEW（转人工）而不是抛异常——
    // 少一个结论不该让整次审核流程失败，而「没结论」最安全的解释就是「交给人看」。
    public AuditResult {
        if (status == null) {
            status = AiAuditStatusEnum.REVIEW;
        }
    }

    /**
     * 产出一条「安全」结论：AI 确信内容没问题，调用方可以据此自动通过并公开
     *
     * <p>为什么这样设计：结论必须由这组工厂方法产出，而不是让调用方直接
     * {@code new AuditResult(AiAuditStatusEnum.SAFE, reason)}。两个理由：
     * 一是「有哪些结论」被收敛成四个有名字的入口，读代码的人一眼能看出这次是放行、
     * 驳回、转人工还是压根没判成，不必去猜枚举常量；
     * 二是让「结论」与「后续动作」保持分离——本类只回答「机器判了什么」，
     * 「要不要改 status、要不要写驳回理由」由 Service 决定，
     * 这样换一套处置策略时不用改判定代码。
     * 安全结论的 reason 只会写进 ai_reason 给管理员看，不展示给作者，
     * 因此调用方可以放心把判定依据写得具体一点。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 选定结论枚举 SAFE——它是「机器判的、且判得很确定」的那一档；
     *     这一档选错会直接把内容放行，是四个工厂里后果最重的一个；</li>
     *     <li>② 把结论与理由交给紧凑构造器。构造器只兜住 status 为 null 的情况，
     *     这里传的是常量，不会触发兜底。</li>
     * </ol>
     *
     * <p>容易踩的坑：safe 是四个工厂里唯一会让内容「立刻公开」的一个，
     * 因此调用点必须确认「模型确实给出了明确的安全判断」，而不是
     * 「解析失败」「服务不可用」这类没判成的情况——那些属于 failed。
     * 把「没判成」写成 safe，等于给所有审核故障开了一个自动放行的后门，
     * 而且它不会报错，只会让不该公开的内容安静地出现在列表里。
     *
     * @param reason 判定理由，可为 null（为空时落库前会兜底成结论的描述）；
     *               它会写进 ai_reason 供管理员参考，不展示给作者
     * @return 结论对象，status 恒为 SAFE，永不为 null
     */
    public static AuditResult safe(String reason) {
        return new AuditResult(AiAuditStatusEnum.SAFE, reason);
    }

    /**
     * 产出一条「违规」结论：AI 确信内容有问题，调用方可以据此自动驳回
     *
     * <p>为什么这样设计：这一档的 reason 会被写进 reject_reason 直接展示给作者，
     * 因此它和 safe / review / failed 的 reason 用途完全不同——后三者的 reason
     * 只进 ai_reason 给管理员参考。同一个字段在两种读者之间分流，
     * 是刻意不做「两个 reason 参数」的结果：模型一次只给一个理由，
     * 拆成两个字段会立刻出现「作者看到的理由和管理员看到的不一致」，
     * 而排查时又说不清哪一份才是模型的原话。
     * 另一方面，结论与「写不写驳回理由」也分开：本方法只表态，落库动作在 Service 里，
     * 这样「AI 判违规」与「人工判违规」可以共用同一段写库代码。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 选定结论枚举 BLOCKED——它是「机器判的、且判得很确定」的负面那一档；
     *     与 REVIEW 的边界是「确定」而不是「严重」：拿不准的内容必须走 review，
     *     否则会把一篇只是措辞可疑的正常文章直接判死；</li>
     *     <li>② 与理由一起构造结论对象，交给调用方决定怎么落库。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要因为「内容看着不舒服」就调本方法。BLOCKED 会让内容
     * 立刻变成已驳回并带着理由回到作者手里，而作者往往没有申诉入口；
     * 真正该拦的是明确违规的内容，模糊地带应当交给 review，
     * 让人工在管理端看一眼——这也是三分法里「灰色」那一档存在的意义。
     *
     * @param reason 驳回理由，可为 null（为空时落库前会兜底成结论的描述）；
     *               非 null 时会被写进 reject_reason 直接展示给作者，
     *               因此文案要写成「作者看了知道该改哪里」的口吻
     * @return 结论对象，status 恒为 BLOCKED，永不为 null
     */
    public static AuditResult blocked(String reason) {
        return new AuditResult(AiAuditStatusEnum.BLOCKED, reason);
    }

    /**
     * 产出一条「灰色」结论：模型拿不准，内容保持待审、交给人看
     *
     * <p>为什么这样设计：这是三分法里最容易被删掉、却最不能删的一档。
     * 模型不是全知全能，它对一部分内容本来就不确定；如果逼它在
     * 「放行」与「驳回」里二选一，不确定性就会被硬转成误判——要么放过风险内容，
     * 要么冤枉正常作者。留一档「我说不准，交给人」才是这套机制
     * 既能减负、又不失守的关键。
     * 关键是：本结论<b>不改变内容的审核状态</b>，只把建议写进 ai_reason。
     * 原因是「待审」本身就是正确状态——灰色内容本来就该等人来看，
     * 若在这里顺手改成驳回，人工复核时反而要去撤销一个没有依据的决定。
     * 同理，它也不写 reject_reason：还没定性，不该给作者一个「你违规了」的暗示。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 选定结论枚举 REVIEW，与 FAILED 区分开——REVIEW 表示
     *     「模型看懂了但拿不准」，FAILED 表示「这次根本没判成」，
     *     管理端靠这两个值判断是该「重点复核」还是该「查服务」；</li>
     *     <li>② 与理由一起构造结论对象；理由会写进 ai_reason，
     *     人工复核时能看到模型当时在犹豫什么。</li>
     * </ol>
     *
     * <p>容易踩的坑：解析模型输出失败时该用 failed 还是 review，是这一层最容易搞混的地方。
     * 判据是「有没有拿到模型的有效输出」：输出为空、调用超时、服务不可用属于 failed
     * （这次没判）；输出拿到了但不是约定的 JSON、字段缺失属于 review
     * （模型说了话，只是没按格式说）。两者最终都不改状态，但排查方向完全不同——
     * 把它们混成一个值，会把「服务商故障」藏进「模型拿不准」里，故障永远查不出来。
     *
     * @param reason 模型给出的理由或「无法解析」的说明，可为 null；
     *               它会写进 ai_reason 供人工复核参考，不展示给作者
     * @return 结论对象，status 恒为 REVIEW，永不为 null；
     *         调用方据此保持内容为待审，不要改成驳回或通过
     */
    public static AuditResult review(String reason) {
        return new AuditResult(AiAuditStatusEnum.REVIEW, reason);
    }

    /**
     * 产出一条「判定失败」结论：这次根本没判成（未启用 / 调用失败 / 输出为空），转人工
     *
     * <p>为什么这样设计：它与 review 一样都不改审核状态，但语义完全不同，必须分成两个值。
     * REVIEW 是「模型给出了判断，只是它自己也不确定」，FAILED 是「模型没给出任何可用判断」。
     * 合成一个值会造成两类损失：管理端无法区分「这条需要重点看」与「AI 这一整段时间都挂了」，
     * 后者是需要运维介入的信号；而把故障信号藏进「排队中/拿不准」里，
     * 是最难被发现的一类问题——接口全部返回成功，只是所有内容都悄悄转成了人工。
     * 另一个关键取舍是：判定失败时<b>绝不能默认放行</b>。没审过的内容不等于安全内容，
     * 所以这里给的是「保持待审」而不是 safe——降级的方向只能是更保守，不能是更宽松。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 选定结论枚举 FAILED，刻意与 REVIEW 分开，让「服务故障」在数据上可见；</li>
     *     <li>② 与理由一起构造结论对象；理由会被写进 ai_reason，
     *     管理员在列表里能直接看到「AI 审核未启用」「AI 服务不可用」这类原因，
     *     不必去翻日志才知道为什么这条没人处置。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法是「审核链路的出口」，调用点很多，但每一个都必须是
     * 真正的失败分支。常见的误用是把它当成「懒得判」的兜底——例如内容为空时
     * 顺手返回 failed，看上去没问题，实际会让管理端堆满「判定失败」的内容，
     * 而运维会照着这个信号去查 AI 服务，方向从一开始就错了。
     * 另外它同样不写 reject_reason：没判成不等于违规，作者不该收到驳回通知。
     *
     * @param reason 失败原因，可为 null；建议写成管理员能据此行动的话，
     *               例如「AI 审核未启用，转人工」而不是「error」
     * @return 结论对象，status 恒为 FAILED，永不为 null；
     *         调用方据此保持内容为待审，并把 reason 记进 ai_reason
     */
    public static AuditResult failed(String reason) {
        return new AuditResult(AiAuditStatusEnum.FAILED, reason);
    }
}

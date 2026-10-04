package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 重建知识库文档请求（管理端，）
 *
 * author: shaoshing
 *
 * <p><b>入参是文档 ID 而不是 {@code (sourceType, sourceId)}</b>：
 * 管理员的操作路径是「在列表里看到某篇文档 → 点重建」，此刻他手上只有 docId；
 * 让他再去查业务 ID 是把内部存储细节暴露给用户。
 * {@code sourceType / sourceId} 由 Service 从文档行里读出来，再走 sync 的既有流程。
 *
 * <p><b>为什么手工录入（{@code manual}）的文档不能重建</b>：重建的本质是
 * 「回到业务源重新读一遍正文」，而 manual 类文档的正文只存在于 chunk 里
 * （原文没有落库，也没有可回读的业务主键）。从 chunk 拼回来的正文会丢失原有切分意图，
 * 拼错一次就是永久性的脏数据。因此明确拒绝并引导到「删除后重新手工录入」，
 * 而不是静默拼接——静默的错比重试的麻烦贵得多。
 */
@Data
public class KnowledgeRebuildRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待重建的文档 ID（必填）
     */
    @NotNull(message = "文档 id 不能为空")
    @Positive(message = "文档 id 必须为正整数")
    private Long id;
}

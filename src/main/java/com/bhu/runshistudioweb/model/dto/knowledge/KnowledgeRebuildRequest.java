package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 重建知识库文档请求（管理端）
 *
 * 入参是文档 ID 而不是 (sourceType, sourceId)：管理员在列表里点重建时手上只有 docId，
 * 让他再去查业务 ID 等于暴露内部存储细节；业务 ID 由 Service 从文档行读出，再走 sync 的既有流程。
 *
 * manual 类文档不能重建：重建要回到业务源重读正文，而 manual 的正文只存在于 chunk 里，
 * 从 chunk 拼回来会丢失原切分意图，拼错一次就是永久脏数据，故明确拒绝并引导「删除后重新录入」。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeRebuildRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待重建的文档 ID（必填） */
    @NotNull(message = "文档 id 不能为空")
    @Positive(message = "文档 id 必须为正整数")
    private Long id;
}

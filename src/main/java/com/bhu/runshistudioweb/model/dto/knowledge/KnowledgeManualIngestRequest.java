package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 手工录入知识库文档请求
 *
 * author: shaoshing
 *
 * <p>只有标题与正文两个字段：手工录入的文档<b>没有业务来源</b>
 * （{@code source_type = manual}、{@code source_id = null}），
 * 因此它<b>不参与幂等判定</b>——每次调用都会新建一篇文档。
 * 这与「业务来源同步」（{@code KnowledgeSyncRequest}）是两种语义，不要合并成一个接口：
 * 合并后要么手工录入被误判为重复，要么同步失去幂等能力。
 *
 * <p>长度限制的理由都在「钱」上：正文上限 20000 字，再往上切出来的块会很多，
 * 每次重建都要重算全部向量——Embedding 调用既慢又按量计费，
 * 没有上限的接口等于把"一次误操作"变成"一笔账单"。
 */
@Data
public class KnowledgeManualIngestRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档标题（必填，≤128，与 DDL 的 {@code title varchar(128)} 对齐）
     */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 128, message = "文档标题不能超过 128 个字符")
    private String title;

    /**
     * 文档正文（必填，≤20000）
     */
    @NotBlank(message = "文档正文不能为空")
    @Size(max = 20000, message = "文档正文不能超过 20000 个字符")
    private String content;
}

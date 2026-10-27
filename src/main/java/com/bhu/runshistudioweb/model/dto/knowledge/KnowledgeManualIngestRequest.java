package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 手工录入知识库文档请求
 *
 * 只有标题与正文：手工文档没有业务来源（source_type = manual、source_id = null），
 * 不参与幂等判定，每次调用都新建一篇。与「业务来源同步」（KnowledgeSyncRequest）是两种语义，
 * 合并后要么手工录入被误判为重复，要么同步失去幂等能力。
 *
 * 正文上限 20000 字是因为 Embedding 按量计费：块越多，每次重建重算向量的成本越高，
 * 没有上限的接口等于把「一次误操作」变成「一笔账单」。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeManualIngestRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档标题（必填，≤128，与 DDL 的 title varchar(128) 对齐） */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 128, message = "文档标题不能超过 128 个字符")
    private String title;

    /** 文档正文（必填，≤20000） */
    @NotBlank(message = "文档正文不能为空")
    @Size(max = 20000, message = "文档正文不能超过 20000 个字符")
    private String content;
}

package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 手工录入知识库文档请求
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

    /**
     * 一次性请求令牌（选填）：打开录入弹窗时生成，重复点击复用同一个值。
     * 为空时服务端跳过幂等检查，兼容旧客户端。
     */
    @Size(max = 64, message = "请求令牌过长")
    private String requestId;
}

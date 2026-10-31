package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 重建知识库文档请求（管理端）
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

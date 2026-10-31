package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索命中的一条资料来源（RAG 溯源）
 *
 * @author shaoshing
 */
@Data
public class KnowledgeSourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 命中的知识库文档 ID（雪花 ID，序列化为字符串，由全局配置完成） */
    private Long docId;
    /** 文档标题（如项目名、成员姓名），直接展示给用户看 */
    private String title;

    /** 来源类型：manual / project / member / certificate */
    private String sourceType;

    /** 来源业务数据 ID（雪花 ID，序列化为字符串）；manual 来源为 null */
    private Long sourceId;

    private Double score;
}

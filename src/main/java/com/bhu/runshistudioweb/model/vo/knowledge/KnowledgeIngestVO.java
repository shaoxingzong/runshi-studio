package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库入库结果
 *
 * @author shaoshing
 */
@Data
public class KnowledgeIngestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 ID（雪花算法 19 位，由全局配置序列化为字符串） */
    private Long docId;

    /** 文档标题 */
    private String title;

    /** 切分块数（与 studio_knowledge_chunk 的实际行数一致） */
    private Integer chunkCount;

    /** 索引状态：1-已索引，2-失败（可重试） */
    private Integer status;

    private boolean skipped;

    /** 是否为重建：true 表示替换了旧块（旧块已逻辑删除、旧向量已清理） */
    private boolean rebuilt;
}

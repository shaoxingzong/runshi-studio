package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库入库结果
 *
 * 回答调用方最关心的四件事：哪篇文档（docId）；切了几块、成功没有（chunkCount + status）；
 * 这次到底做没做（skipped，内容哈希没变时为 true，即零调用零写库，前端据此提示「无需重建」）；
 * 是新建还是重建（rebuilt 表示旧块已被替换）。
 *
 * docId 是雪花 ID，靠全局 JsonConfig 序列化成字符串，这里不要再加 @JsonSerialize——全局约定已覆盖；
 * chunkCount / status 用 Integer，因为它们是计数与状态而不是 ID（用 Long 会变成 "3"）。
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

    /** 是否被跳过：内容哈希未变 → true，本次未调用 Embedding、未写库 */
    private boolean skipped;

    /** 是否为重建：true 表示替换了旧块（旧块已逻辑删除、旧向量已清理） */
    private boolean rebuilt;
}

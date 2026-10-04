package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 知识库入库结果
 *
 * author: shaoshing
 *
 * <p>六个字段回答了调用方最关心的四件事：
 * <ul>
 *     <li><b>哪篇文档</b>：{@code docId}；</li>
 *     <li><b>切了几块、成功没有</b>：{@code chunkCount} + {@code status}
 *     （1-已索引 / 2-失败，失败要能重试）；</li>
 *     <li><b>这次到底做没做</b>：{@code skipped}——内容哈希没变时为 true，
 *     表示「零调用零写库」，前端/脚本据此判断要不要提示"无需重建"；</li>
 *     <li><b>是新建还是重建</b>：{@code rebuilt}——重建意味着旧块被替换，
 *     运维排查时要知道这一点。</li>
 * </ul>
 *
 * <p><b>类型约定</b>：{@code docId} 是雪花 ID，靠<b>全局 JsonConfig</b> 序列化成字符串，
 * 这里<b>不要</b>再加 {@code @JsonSerialize} 注解——全局约定已经覆盖，
 * 局部加注解属于重复且容易遗漏其它字段；
 * {@code chunkCount} / {@code status} 用 {@code Integer}，
 * 因为它们是「计数/状态」而不是 ID，必须让前端拿到<b>数字</b>（若用 Long 会变成 "3"）。
 */
@Data
public class KnowledgeIngestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档 ID（雪花算法 19 位，由全局配置序列化为字符串）
     */
    private Long docId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 切分块数（与 studio_knowledge_chunk 的实际行数一致）
     */
    private Integer chunkCount;

    /**
     * 索引状态：1-已索引，2-失败（可重试）
     */
    private Integer status;

    /**
     * 是否被跳过：内容哈希未变 → true，本次未调用 Embedding、未写库
     */
    private boolean skipped;

    /**
     * 是否为重建：true 表示替换了旧块（旧块已逻辑删除、旧向量已清理）
     */
    private boolean rebuilt;
}

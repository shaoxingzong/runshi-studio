package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档列表项（管理端，）
 *
 * author: shaoshing
 *
 * <p><b>刻意不含正文</b>：正文并不在 doc 行里，它已经被切分存进
 * {@code studio_knowledge_chunk}（一份文档 N 行块）。把正文拼回来再返回，
 * 意味着「列表一页 10 条」就要读回几百行块并拼接——纯粹的浪费。
 * 要看内容请走块数据或重新读业务源；这个 VO 只回答「有哪些文档、各自什么状态」。
 *
 * <p><b>为什么需要它</b>：入库是幂等的、可重试的，但管理员此前<b>看不见</b>库里有什么——
 * 不知道哪些文档 status=2（失败待重试）、哪些是手工录入的孤儿数据。
 * 列表是「看得见」的第一步，删除与重建都依赖它给出的 docId。
 *
 * <p><b>类型约定（与 {@link KnowledgeIngestVO} 同源）</b>：{@code docId / sourceId}
 * 是雪花 ID，靠<b>全局 JsonConfig</b> 序列化成字符串，这里<b>不</b>再加局部注解；
 * {@code status / chunkCount} 用 {@code Integer}（它们是状态与计数，不是 ID），
 * 这样前端拿到的是数字而不是 "3"。
 */
@Data
public class KnowledgeDocVO implements Serializable {

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
     * 来源类型：{@code manual / project / member / certificate}
     */
    private String sourceType;

    /**
     * 来源业务数据 ID（{@code manual} 来源为 null）
     */
    private Long sourceId;

    /**
     * 索引状态：0-待处理，1-已索引，2-失败（可重试）
     *
     * <p>管理员主要看这个字段：等于 2 的文档说明上次入库失败，改完配置点「重建」即可
     */
    private Integer status;

    /**
     * 已切分块数
     */
    private Integer chunkCount;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间（重建会刷新它）
     */
    private LocalDateTime updatedAt;
}

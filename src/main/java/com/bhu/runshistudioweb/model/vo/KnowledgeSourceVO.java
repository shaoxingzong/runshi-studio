package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索命中的一条资料来源（RAG 溯源，）
 *
 * author: shaoshing
 *
 * <p>回答里带上它，是为了让「AI 说的这句话从哪来」可被<a href="#">追溯</a>：
 * 前端可以把它渲染成「参考：项目案例《XXX》」，用户点得回去，
 * 管理员也能一眼看出回答是依据资料还是模型自己在编。
 *
 * <p><b>{@code docId} / {@code sourceId} 是雪花 ID（19 位），序列化后是字符串</b>：
 * JS 的 Number 只有 53 位有效精度，直接传数字会精度丢失，前端拿它去调详情接口就会 404。
 * 这个转换由<b>全局 {@code JsonConfig}</b>（Long → String）统一完成，
 * 这里<b>刻意不加</b>字段级 {@code @JsonSerialize}：与 {@link KnowledgeIngestVO} 保持同一口径，
 * 也符合 DESIGN §4.3「禁止局部覆盖全局序列化约定」——
 * 局部注解的问题是：加了 A 字段忘了 B 字段，就会漏出一个数字 ID，而这种 bug 只在前端调详情时才暴露。
 *
 * <p><b>{@code score} 是相似度，不是置信度</b>：它是向量余弦相似度（0~1，越高越像），
 * 只是排序依据与阈值过滤依据，<b>不要</b>当成「答案正确的概率」展示给用户
 * （那会误导，且不同向量模型的分值分布并不可比）。
 */
@Data
public class KnowledgeSourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中的知识库文档 ID（<b>雪花 ID，序列化为字符串</b>，由全局配置完成）
     *
     * <p>它是溯源的第一落点：一条文档对应一篇正文，chunk 只是它的切分块
     */
    private Long docId;

    /**
     * 文档标题（如项目名、成员姓名），直接展示给用户看
     */
    private String title;

    /**
     * 来源类型：{@code manual} / {@code project} / {@code member} / {@code certificate}
     *
     * <p>前端据此决定「点进去看什么」：项目跳项目详情、成员跳成员详情
     */
    private String sourceType;

    /**
     * 来源业务数据 ID（<b>雪花 ID，序列化为字符串</b>）；{@code manual} 来源为 null
     */
    private Long sourceId;

    /**
     * 该条资料与本次提问的相似度（0~1，越高越相关）
     */
    private Double score;
}

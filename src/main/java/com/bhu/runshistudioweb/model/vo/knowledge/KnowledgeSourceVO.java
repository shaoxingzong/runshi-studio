package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索命中的一条资料来源（RAG 溯源）
 *
 * 带上它是为了让「AI 说的这句话从哪来」可被追溯：前端可渲染成「参考：项目案例《XXX》」，
 * 管理员也能一眼看出回答是依据资料还是模型自己在编。
 *
 * docId / sourceId 是雪花 ID，由全局 JsonConfig 统一转成字符串（JS 的 Number 只有 53 位精度，
 * 直接传数字前端拿它去调详情接口就会 404）。这里刻意不加字段级 @JsonSerialize：
 * 加了 A 字段忘了 B 字段就会漏出一个数字 ID，而这种 bug 只在前端调详情时才暴露。
 *
 * score 是向量余弦相似度（0~1），只是排序与阈值过滤的依据，不要当成「答案正确的概率」展示给用户。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeSourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 命中的知识库文档 ID（雪花 ID，序列化为字符串，由全局配置完成）
     *
     * 它是溯源的第一落点：一条文档对应一篇正文，chunk 只是它的切分块
     */
    private Long docId;
    /** 文档标题（如项目名、成员姓名），直接展示给用户看 */
    private String title;

    /**
     * 来源类型：manual / project / member / certificate
     *
     * 前端据此决定「点进去看什么」：项目跳项目详情、成员跳成员详情
     */
    private String sourceType;

    /** 来源业务数据 ID（雪花 ID，序列化为字符串）； manual 来源为 null */
    private Long sourceId;

    /** 该条资料与本次提问的相似度（0~1，越高越相关） */
    private Double score;
}

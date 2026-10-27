package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档列表项（管理端）
 *
 * 刻意不含正文：正文不在 doc 行里，它已被切分存进 studio_knowledge_chunk，
 * 把正文拼回来意味着「列表一页 10 条」要读回几百行块并拼接。这个 VO 只回答「有哪些文档、各自什么状态」。
 *
 * 列表是「看得见」的第一步：管理员此前看不见库里有什么，不知道哪些文档 status=2（失败待重试）、
 * 哪些是手工录入的孤儿数据，而删除与重建都依赖它给出的 docId。
 *
 * docId / sourceId 是雪花 ID，靠全局 JsonConfig 序列化成字符串，这里不再加局部注解；
 * status / chunkCount 用 Integer（状态与计数，不是 ID），这样前端拿到的是数字而不是 "3"。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档 ID（雪花算法 19 位，由全局配置序列化为字符串） */
    private Long docId;

    /** 文档标题 */
    private String title;

    /** 来源类型： manual / project / member / certificate */
    private String sourceType;

    /** 来源业务数据 ID（ manual 来源为 null） */
    private Long sourceId;

    /**
     * 索引状态：0-待处理，1-已索引，2-失败（可重试）
     *
     * 管理员主要看这个字段：等于 2 的文档说明上次入库失败，改完配置点「重建」即可
     */
    private Integer status;

    /** 已切分块数 */
    private Integer chunkCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（重建会刷新它） */
    private LocalDateTime updatedAt;
}

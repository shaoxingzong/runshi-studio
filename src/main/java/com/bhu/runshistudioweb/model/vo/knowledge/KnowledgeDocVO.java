package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档列表项（管理端）
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

    /** 来源类型：manual / project / member / certificate */
    private String sourceType;

    /** 来源业务数据 ID（ manual 来源为 null） */
    private Long sourceId;

    /** 索引状态：0-待处理，1-已索引，2-失败（可重试） */
    private Integer status;

    /** 已切分块数 */
    private Integer chunkCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（重建会刷新它） */
    private LocalDateTime updatedAt;
}

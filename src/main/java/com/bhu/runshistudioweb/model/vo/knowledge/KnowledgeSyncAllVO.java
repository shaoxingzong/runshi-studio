package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 全量同步结果统计
 *
 * 类型全是 Integer：它们是计数而不是 ID，必须让前端拿到数字（用 Long 会被全局配置序列化成 "5"）。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeSyncAllVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer total;

    /** 新建成功的数量（库里原本没有这篇文档） */
    private Integer created;

    private Integer rebuilt;

    /** 跳过的数量（内容哈希未变，零 Embedding 调用、零写库） */
    private Integer skipped;

    /** 失败的数量（源读取失败或向量化失败；已计入日志，可重跑本接口重试） */
    private Integer failed;
}

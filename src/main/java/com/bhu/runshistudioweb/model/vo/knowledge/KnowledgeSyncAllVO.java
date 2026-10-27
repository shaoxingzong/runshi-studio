package com.bhu.runshistudioweb.model.vo.knowledge;

import lombok.Data;

import java.io.Serializable;

/**
 * 全量同步结果统计
 *
 * 五个数字互斥且完备：created + rebuilt + skipped + failed == total。这个等式是调用方与测试判断
 * 「有没有漏」的依据，新增分支时必须同步维护它。
 *
 * 类型全是 Integer：它们是计数而不是 ID，必须让前端拿到数字（用 Long 会被全局配置序列化成 "5"）。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeSyncAllVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本次尝试同步的文档总数 */
    private Integer total;

    /** 新建成功的数量（库里原本没有这篇文档） */
    private Integer created;

    /** 重建成功的数量（内容有变，旧块已替换、旧向量已清理） */
    private Integer rebuilt;

    /** 跳过的数量（内容哈希未变，零 Embedding 调用、零写库） */
    private Integer skipped;

    /** 失败的数量（源读取失败或向量化失败；已计入日志，可重跑本接口重试） */
    private Integer failed;
}

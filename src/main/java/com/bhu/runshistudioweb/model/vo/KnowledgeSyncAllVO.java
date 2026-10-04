package com.bhu.runshistudioweb.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 全量同步结果统计
 *
 * author: shaoshing
 *
 * <p><b>五个数字互斥且完备</b>：{@code created + rebuilt + skipped + failed == total}。
 * 这个等式是调用方（以及测试）判断"有没有漏"的依据，新增分支时必须同步维护它。
 *
 * <p><b>为什么要区分 created 与 rebuilt</b>：两者都真的调用了 Embedding（都要花钱），
 * 但 rebuilt 意味着旧块被替换、旧向量被清理；运维排查「为什么检索结果变了」时，
 * 知道是新建还是重建能省很多时间。
 *
 * <p><b>为什么要单独有 failed</b>：全量同步是「N 次幂等 sync 的循环」，
 * 单条失败不中断其它条；如果把它并进 skipped，运维会误以为"都处理过了"，
 * 而实际上那些文档根本没进库。
 * <b>全部失败</b>与<b>全部跳过</b>因此是两个完全不同的信号，必须能区分。
 *
 * <p><b>类型全部是 {@code Integer}</b>：它们是计数而不是 ID，
 * 必须让前端拿到数字（用 Long 会被全局配置序列化成 "5"）。
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

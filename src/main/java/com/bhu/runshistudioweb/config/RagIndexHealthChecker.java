package com.bhu.runshistudioweb.config;

import com.bhu.runshistudioweb.manager.RagVectorStore;
import com.bhu.runshistudioweb.mapper.StudioKnowledgeChunkMapper;
import com.bhu.runshistudioweb.model.vo.knowledge.KnowledgeSyncAllVO;
import com.bhu.runshistudioweb.service.KnowledgeDocService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 启动健康检查：发现「切分块有数据、向量索引不可用」时自动重建
 *
 * <p>为什么需要它：向量索引是派生数据，可能因为 Redis 索引被清空、向量模型换了、
 * 历史残留或人工误操作而与数据库不一致。以前的表现是：应用启动成功，
 * 但 AI 永远检索不到资料，也不报错，只能靠管理员想起「点一下重建向量」。
 * 这里在启动完成后主动比对一次数量，发现不一致就异步重建。
 *
 * @author shaoshing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagIndexHealthChecker {

    private final RagVectorStore ragVectorStore;

    private final StudioKnowledgeChunkMapper chunkMapper;

    private final KnowledgeDocService knowledgeDocService;

    private final AiProperties aiProperties;

    /** 重建在虚拟线程里跑：不占平台线程，不阻塞应用启动后的其它请求 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 启动完成后检查一次索引状态
     *
     * <p>执行步骤：
     * <ol>
     *     <li>开关关闭 / 非 redis 模式：只打日志，不做任何重建；</li>
     *     <li>查数据库存活切分块数与向量数，打印一行可运维核对的状态；</li>
     *     <li>数量不一致或索引标记为需重建：清空索引，异步 reindex-all；</li>
     *     <li>任何异常只记日志，不阻断应用启动。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在 chunks=0 时也重建——空知识库是正常状态，
     * 此时重建没有任何文档可做，只会白打一条失败日志。反过来，
     * chunks=0 但 vectors>0 说明有孤儿向量，需要清空。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (!Boolean.TRUE.equals(aiProperties.getRagStartupCheckEnabled())) {
            log.info("RAG 启动健康检查已关闭（studio.ai.rag-startup-check-enabled=false）");
            return;
        }
        if (!"redis".equalsIgnoreCase(aiProperties.getVectorStoreType())) {
            // memory 模式重启后本来就会清空，保留原有的手动重建路径
            log.info("RAG 启动健康检查：memory 模式跳过自动重建（需要恢复时手动调用 reindex-all）");
            return;
        }

        try {
            long chunks = chunkMapper.selectCount(null);
            int vectors = ragVectorStore.count();
            boolean needsRebuild = ragVectorStore.needsRebuild();
            log.info("RAG 索引状态 | type=redis | chunks={} | vectors={} | model={} | needsRebuild={}",
                    chunks, vectors, aiProperties.getEmbeddingModel(), needsRebuild);

            if (chunks == 0) {
                if (vectors > 0) {
                    log.warn("RAG 索引存在孤儿向量（数据库无切分块），清空索引 | vectors={}", vectors);
                    ragVectorStore.reset();
                }
                return;
            }
            if (vectors == chunks && !needsRebuild) {
                return;
            }

            log.warn("RAG 索引与数据库不一致，准备清空并异步重建 | chunks={} | vectors={} | needsRebuild={}",
                    chunks, vectors, needsRebuild);
            ragVectorStore.reset();
            executor.submit(this::rebuildAsync);
        } catch (Exception e) {
            log.error("RAG 启动健康检查失败（不阻断应用启动）", e);
        }
    }

    /**
     * 异步执行全量重建，并把结果写进日志
     */
    private void rebuildAsync() {
        try {
            KnowledgeSyncAllVO summary = knowledgeDocService.reindexAll();
            log.info("RAG 索引自动重建完成 | total={} rebuilt={} skipped={} failed={}",
                    summary.getTotal(), summary.getRebuilt(), summary.getSkipped(), summary.getFailed());
        } catch (Exception e) {
            log.error("RAG 索引自动重建失败，可稍后手动调用 /knowledge/doc/reindex-all 重试", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

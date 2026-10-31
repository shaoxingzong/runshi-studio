package com.bhu.runshistudioweb.config;

import cn.hutool.core.util.StrUtil;
import com.bhu.runshistudioweb.manager.MemoryRagVectorStore;
import com.bhu.runshistudioweb.manager.RagVectorStore;
import com.bhu.runshistudioweb.manager.RedisRagVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

/**
 * 向量库装配：按 studio.ai.vector-store-type 选择 memory 或 redis
 *
 * @author shaoshing
 */
@Slf4j
@Configuration
public class RagVectorStoreConfig {

    /**
     * 向量库 Bean
     *
     * @param aiProperties AI 配置（向量库类型、向量模型名）
     * @param host Redis 主机，沿用 spring.data.redis.host
     * @param port Redis 端口
     * @param password Redis 口令，空表示无口令
     * @param database Redis 库号
     * @return memory 或 redis 实现；配置非法/Redis 模块缺失时抛 IllegalStateException
     */
    @Bean
    public RagVectorStore ragVectorStore(
            AiProperties aiProperties,
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {

        String type = StrUtil.blankToDefault(aiProperties.getVectorStoreType(), "memory")
                .trim().toLowerCase();
        if ("memory".equals(type)) {
            log.info("RAG 向量库类型=memory（重启会丢失向量，仅适用于开发/测试）");
            return new MemoryRagVectorStore();
        }
        if (!"redis".equals(type)) {
            throw new IllegalStateException(
                    "studio.ai.vector-store-type 只支持 memory / redis，当前值=" + aiProperties.getVectorStoreType());
        }
        return createRedisStore(aiProperties, host, port, password, database);
    }

    /** 创建 Redis 向量库 */
    private RagVectorStore createRedisStore(AiProperties aiProperties, String host, int port,
                                            String password, int database) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .database(database)
                .connectionTimeoutMillis(3000)
                .socketTimeoutMillis(3000);
        if (StrUtil.isNotBlank(password)) {
            builder.password(password);
        }

        UnifiedJedis jedis = new UnifiedJedis(new HostAndPort(host, port), builder.build());
        try {
            RagVectorStore store = new RedisRagVectorStore(jedis, aiProperties.getEmbeddingModel());
            log.info("RAG 向量库类型=redis | index=studio-rag-chunks | host={}:{}", host, port);
            return store;
        } catch (RuntimeException e) {
            jedis.close();
            throw new IllegalStateException(
                    "Redis 向量库不可用：需要 Redis 8.4+ 且包含 Query Engine 与 JSON 模块。"
                            + "生产请升级 Redis；本地开发可设置 VECTOR_STORE_TYPE=memory。", e);
        }
    }
}

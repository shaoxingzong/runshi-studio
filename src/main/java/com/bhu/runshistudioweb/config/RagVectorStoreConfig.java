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
 * <p>为什么把选择放在配置类而不是 KnowledgeBaseManager：
 * 向量库是「基础设施」而不是「业务逻辑」。让 Manager 里出现 if (redis) ...
 * 会把部署形态的判断扩散到业务代码；放在这里，Manager 只注入一个
 * {@link RagVectorStore}，两个实现可替换、可测试。
 *
 * <p>为什么生产用 redis 而本地默认 memory：
 * Redis 向量索引需要 Redis 8.4+ 的 Query Engine + JSON 模块，本地开发者
 * 不应为了跑通项目被迫升级 Redis；测试也不应该依赖外部索引。生产 prod profile
 * 显式切到 redis，保证重启与多实例不丢向量。
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
     * @param host         Redis 主机，沿用 spring.data.redis.host
     * @param port         Redis 端口
     * @param password     Redis 口令，空表示无口令
     * @param database     Redis 库号
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

    /**
     * 创建 Redis 向量库
     *
     * <p>连接参数直接复用 spring.data.redis 的配置，避免再维护一份 Redis 地址/口令；
     * 这里会额外建一个 Jedis 连接（Spring Data Redis 用的是 Lettuce），两者可以共存。
     *
     * <p>初始化失败必须是启动失败：RAG 是生产功能，带着「向量库不可用」上线，
     * 表现会是 AI 永远说「没有资料」而不报错，比直接启动失败更难排查。
     * 错误信息要写清升级路径，半夜排障的人不需要再翻代码。
     */
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

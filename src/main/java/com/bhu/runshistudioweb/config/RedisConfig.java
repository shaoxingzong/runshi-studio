package com.bhu.runshistudioweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * Redis 配置
 *
 * author: shaoshing
 *
 * <p>核心是四点序列化器的选择：
 * <ul>
 *     <li>key / hashKey 必须用 {@link StringRedisSerializer}：默认的 JdkSerializationRedisSerializer 会把 key
 *     写成 {@code \xac\xed\x00\x05t\x00\x04user} 这种带二进制前缀的内容，redis-cli 里读不懂，其他语言的客户端也无法对接；</li>
 *     <li>value / hashValue 用 JSON 序列化器：可读、跨语言，且体积明显小于 JDK 序列化。</li>
 * </ul>
 *
 * <p>注意：Spring Boot 4 内置 Jackson 3，序列化器要选 {@link GenericJacksonJsonRedisSerializer}
 * （Jackson 3 版），而不是 Jackson 2 的 GenericJackson2JsonRedisSerializer。
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate<String, Object>：key 走 String 序列化，value 走 JSON 序列化
     *
     * @param connectionFactory Spring Boot 依据 spring.data.redis 配置自动装配的连接工厂（Lettuce）
     * @return 可直接注入使用的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer jsonSerializer = valueSerializer();

        // key 与 hashKey：String 序列化
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);

        // value 与 hashValue：JSON 序列化（两者必须成对设置，只设一半会导致 Hash 结构写入后读不出来）
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        // RedisTemplate 实现了 InitializingBean，afterPropertiesSet() 由 Spring 容器自动调用，无需手写
        return redisTemplate;
    }

    /**
     * value 用的 JSON 序列化器
     */
    private GenericJacksonJsonRedisSerializer valueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                // 必须开启「默认类型信息」（序列化时写入 @class 字段），否则值类型是 Object，
                // 反序列化回来只能得到 LinkedHashMap，拿不回原来的实体对象。
                // 这里用白名单校验器而不用 enableUnsafeDefaultTyping()：后者允许任意类型，
                // 一旦 Redis 被写入恶意的 @class 内容就会触发反序列化漏洞。
                .enableDefaultTyping(typeValidator())
                // 缓存内的格式与接口返回完全保持一致（Long 转字符串 + 时间格式），
                // 避免同一个对象在「缓存」与「接口响应」里出现两套写法
                .customize(builder -> builder.addModule(redisJsonModule()))
                .build();
    }

    /**
     * 反序列化类型白名单：只允许项目自身类型与常见容器 / 基础类型
     */
    private PolymorphicTypeValidator typeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.bhu.runshistudioweb.") // 项目自己的实体、DTO、VO
                .allowIfSubType("java.util.")               // List、Map、Set 等容器
                .allowIfSubType("java.time.")               // LocalDateTime、LocalDate 等
                .allowIfSubType("java.lang.")               // String、Integer、Long 等包装类型
                .allowIfSubType("java.math.")               // BigDecimal、BigInteger
                .build();
    }

    /**
     * 缓存内的 JSON 规则：与接口返回（JsonConfig）保持完全一致
     * 1. Long 转字符串
     * 2. 时间格式复用 JsonConfig 的定义
     */
    private SimpleModule redisJsonModule() {
        SimpleModule module = new SimpleModule("runshi-redis-json-module");

        // Long 必须与接口一致地序列化为字符串：主键是 19 位雪花 ID，超出 JS Number 安全整数范围。
        // 若缓存里存成裸数字，一旦将来做「直接返回缓存 JSON」的优化，精度丢失就会直达前端。
        // 反序列化无需额外注册：Jackson 默认支持把 "1790493804982123456" 转回 Long。
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // 时间类型直接复用 JsonConfig 中的格式定义，保证「缓存」与「接口返回」完全一致
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(JsonConfig.DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(JsonConfig.LENIENT_DATE_TIME_PARSER));
        return module;
    }
}

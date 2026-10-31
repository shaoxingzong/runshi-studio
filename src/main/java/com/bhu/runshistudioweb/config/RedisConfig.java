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
 * Redis 配置：决定 key 与 value 在 Redis 里各长什么样
 *
 * @author shaoshing
 */
@Configuration
public class RedisConfig {

    /**
     * 构建可直接注入的 RedisTemplate：key 走字符串序列化，value 走 JSON 序列化
     *
     * @param connectionFactory Spring Boot 依据 spring.data.redis 配置自动装配的连接工厂（Lettuce）
     * @return 可直接注入使用的 RedisTemplate；Redis 服务不可用时注入仍成功，要到读写时才失败
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

    /** 构建 value 用的 JSON 序列化器：写入类型信息 + 限定可反序列化的类型范围 */
    private GenericJacksonJsonRedisSerializer valueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator())
                .customize(builder -> builder.addModule(redisJsonModule()))
                .build();
    }

    /** 反序列化类型白名单：只允许项目自身类型与常见容器 / 基础类型 */
    private PolymorphicTypeValidator typeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.bhu.runshistudioweb.") // 项目自己的实体、DTO、VO
                .allowIfSubType("java.util.")               // List、Map、Set 等容器
                .allowIfSubType("java.time.")               // LocalDateTime、LocalDate 等
                .allowIfSubType("java.lang.")               // String、Integer、Long 等包装类型
                .allowIfSubType("java.math.")               // BigDecimal、BigInteger
                .build();
    }

    /** 缓存内的 JSON 规则：与接口返回（JsonConfig）保持完全一致 */
    private SimpleModule redisJsonModule() {
        SimpleModule module = new SimpleModule("runshi-redis-json-module");

        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // 时间类型直接复用 JsonConfig 中的格式定义，保证「缓存」与「接口返回」完全一致
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(JsonConfig.DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(JsonConfig.LENIENT_DATE_TIME_PARSER));
        return module;
    }
}

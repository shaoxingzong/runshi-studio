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
 * 默认的 JDK 序列化会把 key 写成带二进制前缀的内容：redis-cli 里读不懂，其他语言的客户端
 * 也无法对接。本类的选择是「key 用纯字符串 + value 用 JSON」：key 是给人看的，
 * value 是可读的结构化数据，JSON 既跨语言又比 JDK 序列化小得多。
 *
 * 注意本项目是 Boot 4 + Jackson 3（包名 tools.jackson），序列化器要用
 * GenericJacksonJsonRedisSerializer 而不是 Jackson 2 版的 GenericJackson2JsonRedisSerializer。
 *
 * @author shaoshing
 */
@Configuration
public class RedisConfig {

    /**
     * 构建可直接注入的 RedisTemplate：key 走字符串序列化，value 走 JSON 序列化
     *
     * 四个序列化器必须成对设置：普通 key-value 用 key/value，Hash 结构用 hashKey/hashValue。
     * 只设一半的典型症状是普通字符串读写完全正常，一旦用 opsForHash 写入就再也读不出来——
     * 写进去时用了 JDK 序列化、读出来时按 JSON 解析，两边对不上。
     *
     * 坑：不要手动调用 afterPropertiesSet()。RedisTemplate 实现了 InitializingBean，
     * Spring 在初始化阶段会自动调用它。
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

    /**
     * 构建 value 用的 JSON 序列化器：写入类型信息 + 限定可反序列化的类型范围
     *
     * 必须开启类型信息：value 类型是 Object，不写类型信息只能得到 LinkedHashMap，一强转就抛
     * ClassCastException。必须用白名单校验器而不是 enableUnsafeDefaultTyping()：@class 是存在
     * Redis 里的普通文本，允许任意类型就等于把反序列化漏洞的门打开。
     *
     * 坑：白名单按类型名前缀匹配。新增的包若不在 com.bhu.runshistudioweb. 之下，反序列化会直接
     * 失败并报「not allowed by the configured PolymorphicTypeValidator」——看到它就是要补一条白名单。
     *
     * @return 配置好的 JSON 序列化器，供 value 与 hashValue 共用同一个实例
     */
    private GenericJacksonJsonRedisSerializer valueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                // 必须开启「默认类型信息」（序列化时写入 @class 字段），否则值类型是 Object，
                // 反序列化回来只能得到 LinkedHashMap，拿不回实体对象本身。
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
     *
     * 反序列化漏洞的本质是「攻击者能指定要还原成哪个类」。白名单把可选项从
     * 「classpath 上的所有类」压缩到几个明确的包，即使 Redis 内容被篡改也构造不出攻击链。
     * 新增包时必须显式加进来，这种「麻烦」正是它的价值。
     *
     * 坑：allowIfSubType 是前缀匹配，写 "java.util." 时末尾那个点不能省。
     *
     * @return 类型校验器，只在构建序列化器时使用一次
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
     *
     * 两边不一致的后果：缓存里 Long 是裸数字而接口里是字符串，将来做「直接返回缓存 JSON」
     * 这类优化时，精度丢失会绕过接口层直达前端；时间格式不一致时，同一个字段在
     * 「刚写入」与「从缓存读」两种情况下会长得不一样，前端不得不写两套解析。
     *
     * 坑：这里复用的是 JsonConfig 里包级可见的常量，能访问仅仅因为两个类在同一个包下。
     * 把 RedisConfig 挪到别的包会编译失败——这是刻意用「包级可见」表达「两个类必须一起改」。
     *
     * @return 只含 Long 与时间规则的模块，挂在 Redis 的序列化器上
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

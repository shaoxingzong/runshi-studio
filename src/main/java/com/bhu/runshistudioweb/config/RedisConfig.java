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
 * <p>为什么序列化器要一个一个显式设置，而不是用默认值：
 * RedisTemplate 的默认序列化器是 JDK 序列化，它会把 key 写成
 * \xac\xed\x00\x05t\x00\x04user 这种带二进制前缀的内容。后果有三层：
 * <ul>
 *     <li>redis-cli 里几乎读不懂，排障时看不到自己存的是什么；</li>
 *     <li>其他语言的客户端（Python、Go、Node）无法对接，因为它们不知道 Java 的序列化协议；</li>
 *     <li>同一个 key 用 redis-cli 手动查都查不到（你敲的是纯文本 user，存进去的不是）。</li>
 * </ul>
 *
 * <p>本类的选择是「key 用纯字符串 + value 用 JSON」：
 * key 是给人看的（业务前缀 + ID），不需要类型信息；value 是可读的结构化数据，
 * JSON 既跨语言又比 JDK 序列化小得多。
 *
 * <p>注意：本项目跑在 Spring Boot 4 上，内置的是 Jackson 3（包名从 com.fasterxml.jackson
 * 变成了 tools.jackson）。所以序列化器要用 Jackson 3 版的 GenericJacksonJsonRedisSerializer，
 * 而不是 Jackson 2 版的 GenericJackson2JsonRedisSerializer——两者名字只差一个数字，
 * 但用错了要么编译不过，要么在运行期因为找不到类而报错。
 *
 * @author shaoshing
 */
@Configuration
public class RedisConfig {

    /**
     * 构建可直接注入的 RedisTemplate：key 走字符串序列化，value 走 JSON 序列化
     *
     * <p>为什么这样设计：四个序列化器（key / hashKey / value / hashValue）必须成对设置，
     * 因为它们管的是不同的数据结构。普通 key-value 用 key/value 这一对，
     * Hash 结构用 hashKey/hashValue 这一对。只设一半的典型症状是：
     * 普通字符串读写完全正常，一旦用 opsForHash 写入就再也读不出来——
     * 因为写进去时用了 JDK 序列化、读出来时按 JSON 解析，两边对不上。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>创建模板并绑定连接工厂：连接工厂由 Spring Boot 依据 spring.data.redis 配置
     *     自动装配（默认 Lettuce 驱动），这里只是把它接过来，不要自己 new 一个；</li>
     *     <li>准备好两个序列化器实例：String 的负责 key，JSON 的负责 value。
     *     先建好再设置，是为了避免在设置过程中反复创建实例；</li>
     *     <li>设置 key 与 hashKey：必须是 String 序列化，理由见类注释；</li>
     *     <li>设置 value 与 hashValue：必须成对设置，只设一半会导致 Hash 结构写入后读不出来；</li>
     *     <li>返回模板，由 Spring 接管后续的初始化与销毁。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这个方法里手动调用 afterPropertiesSet()。
     * RedisTemplate 实现了 InitializingBean，Spring 在 Bean 初始化阶段会自动调用它；
     * 手动再调一次不会报错，但会让人误以为「不调就不生效」，进而在别处也照着写一遍。
     *
     * @param connectionFactory Spring Boot 依据 spring.data.redis 配置自动装配的连接工厂（Lettuce）；
     *                          传 null 会在首次读写时抛空指针，而不是在这里报错
     * @return 可直接注入使用的 RedisTemplate；返回值本身不为 null，
     *         但 Redis 服务不可用时，注入是成功的，要到真正读写时才失败
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
     * <p>为什么必须开启类型信息：RedisTemplate 的 value 类型是 Object，
     * 存进去时你知道它是 UserVO，取出来时框架只知道「某个 Object」。
     * 不写入类型信息的话，反序列化只能得到一个 LinkedHashMap——
     * 字段都在，但它不是 UserVO，一强转就抛 ClassCastException。
     * 开启后序列化结果里会多一个 @class 字段记录原始类型，取出来才能还原成对象本身。
     *
     * <p>为什么用白名单校验器，而不是 enableUnsafeDefaultTyping()：
     * 后者允许 @class 指向任意类型。而 @class 是存在 Redis 里的普通文本，
     * 任何能写 Redis 的人（配置泄露、内网横向、误操作）都能把它改成
     * 一个在构造/设值时会执行危险动作的类，读取方一反序列化就被利用——
     * 这是 Java 生态里最经典的一类反序列化漏洞。白名单把可还原的类型限制在
     * 「本项目自己的类 + 几个基础包」，即使 Redis 内容被篡改，也构造不出可用的攻击链。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>开启默认类型信息，并挂上白名单校验器：这一步决定「能不能还原成原对象」；</li>
     *     <li>追加与接口一致的 JSON 规则（Long 转字符串、时间格式），
     *     避免同一个对象在缓存里与接口响应里出现两套写法；</li>
     *     <li>构建并返回序列化器。</li>
     * </ol>
     *
     * <p>容易踩的坑：白名单是「按类型名前缀匹配」的，所以将来新增的包如果不在
     * com.bhu.runshistudioweb. 之下（例如引入某个第三方 DTO 直接塞进缓存），
     * 反序列化会直接失败。报错信息是「not allowed by the configured PolymorphicTypeValidator」，
     * 看到它就说明需要往白名单里补一条，而不是去怀疑缓存没写进去。
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
     * <p>为什么这样设计：反序列化漏洞的本质是「攻击者能指定要还原成哪个类」。
     * 白名单把可选项从「classpath 上的所有类」压缩到「几个明确的包」，
     * 攻击者即使能改 Redis 里的 @class，也只能还原成这些包里本来就无害的类型。
     * 这是一条「默认拒绝、逐个放行」的规则，新增包时必须显式加进来——
     * 这种「麻烦」正是它的价值所在。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>放行项目自身的包：实体、DTO、VO 都在这里，不放行的话缓存里什么都读不回来；</li>
     *     <li>放行 java.util：List、Map、Set 等容器，缓存里存集合时必需；</li>
     *     <li>放行 java.time：LocalDateTime、LocalDate 等时间类型；</li>
     *     <li>放行 java.lang：String、Integer、Long 等包装类型；</li>
     *     <li>放行 java.math：BigDecimal、BigInteger，金额与统计类字段会用；</li>
     *     <li>构建校验器。</li>
     * </ol>
     *
     * <p>容易踩的坑：allowIfSubType 是「前缀匹配」，写 "java.util." 时末尾那个点不能省。
     * 省掉会变成匹配所有以 java.util 开头的类型名，虽然实际影响不大，
     * 但语义上就变成了模糊匹配，将来排查白名单问题时容易误判。
     *
     * @return 类型校验器，只在构建序列化器时使用一次，不需要缓存
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
     * <p>为什么不让缓存用一套、接口用另一套：如果缓存里 Long 是裸数字、接口里是字符串，
     * 那么将来做「直接返回缓存 JSON」这类优化时，精度丢失就会绕过接口层直达前端；
     * 反过来，缓存里的时间格式与接口不一致时，同一个字段在「刚写入」和「从缓存读」两种情况下
     * 会长得不一样，前端不得不写两套解析。统一规则的成本只是一次模块复用，收益是消灭一整类不一致。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>创建模块；</li>
     *     <li>注册 Long 的序列化器（包装类型与基本类型各一次）：主键是 19 位雪花 ID，
     *     超出 JS Number 的安全整数范围，必须写成字符串；</li>
     *     <li>注册时间类型的序列化与反序列化，格式化器直接复用 JsonConfig 里的定义；</li>
     *     <li>返回模块。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里复用的是 JsonConfig 里那两个包级可见（没有 public）的常量。
     * 它们能被访问，仅仅因为两个类在同一个包下；一旦把 RedisConfig 挪到别的包，
     * 编译立刻失败。这不是设计缺陷，而是刻意用「包级可见」表达「这两个类是一组、必须一起改」。
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

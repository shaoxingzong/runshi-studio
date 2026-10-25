package com.bhu.runshistudioweb.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * 全局 JSON 序列化与反序列化配置：决定「对象怎么变成响应体里的 JSON」
 *
 * <p>为什么用 Customizer 而不是自己 new 一个 ObjectMapper：
 * Spring MVC 在把 Controller 的返回值写成 JSON 时，用的是容器里那个由 Spring Boot 建好的
 * 序列化器实例，不是你我 new 出来的任何一个。自己 new 一个再注册成普通 Bean，
 * 效果是「容器里多了一个没人用的对象」——接口输出一个字符都不会变，而且完全不报错。
 * 只有注册 Customizer（定制器），Spring Boot 在构建那个实例时才会把本方法里的规则叠加上去。
 * 这是本类唯一需要记住的机制。
 *
 * <p>本类定下两条全局规则，全项目的接口输出都受它们影响：
 * <ul>
 *     <li>null 字段照样输出：前端拦截器要能读到 data 字段，哪怕它的值是 null；</li>
 *     <li>Long 输出成字符串、LocalDateTime 输出成 yyyy-MM-dd HH:mm:ss。</li>
 * </ul>
 *
 * <p>注意：Redis 的序列化规则在 RedisConfig 里单独配，但它刻意复用了本类的两个格式化器，
 * 目的是让「缓存里存的 JSON」与「接口返回的 JSON」长得一模一样。
 *
 * @author shaoshing
 */
@Configuration
public class JsonConfig {

    /**
     * 统一的时间输出格式，避免前端再对 ISO-8601 的 "T" 做二次处理。
     * 包级可见，供 RedisConfig 复用，保证缓存与接口的时间格式一致
     *
     * <p>为什么用 static final 而不是每次调用 DateTimeFormatter.ofPattern：
     * DateTimeFormatter 是不可变且线程安全的，建一次就能全局复用；
     * 而 ofPattern 每次都要解析一遍模式串，放在高频的序列化路径上是白白的开销。
     * 顺带一提，正因为它是线程安全的，才能被 RedisConfig 跨类直接引用。
     */
    static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 时间反序列化格式：同时兼容 "2026-09-27 10:30:05" 与 ISO-8601 的 "2026-09-27T10:30:05"（含毫秒）。
     * 只写死其中一种，前端按另一种格式传参时会直接抛 InvalidFormatException。
     *
     * <p>为什么解析要宽松、输出却严格：输出格式是我们单方面决定的，越统一越好；
     * 而输入来自各种客户端，历史代码、第三方工具、手写测试都可能用另一种写法，
     * 收得越紧，越容易在「格式不对」上浪费排查时间——而这个错误对用户毫无意义。
     */
    static final DateTimeFormatter LENIENT_DATE_TIME_PARSER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    /**
     * 定制全局 JSON 序列化器：让 null 字段保留、Long 变字符串、时间格式化
     *
     * <p>为什么这样设计：Spring Boot 把「构建序列化器」这件事开放成了定制点，
     * 而不是让你替换整个序列化器。这样做的价值在于——框架自己的默认配置（各种模块、
     * 容错开关）全部保留，你只声明「额外还要什么」，升级框架时也不会因为
     * 自己手搓了一个实例而错过新版本的默认行为。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>改默认的字段包含策略为 ALWAYS：Jackson 的默认策略会丢掉值为 null 的字段，
     *     导致成功响应的 data 为 null 时，JSON 里干脆没有 data 这个键，
     *     前端读 res.data 得到 undefined 而不是 null，判断逻辑就会走进意料之外的分支。
     *     这一步必须放在注册模块之前，因为它改的是序列化器的全局配置而不是模块内的规则；</li>
     *     <li>注册自定义模块：把 Long 与 LocalDateTime 的具体写法挂上去，
     *     详见 jsonTypeModule 的说明。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法返回的是 Customizer 而不是序列化器本身，
     * 所以不要试图在这里 new 一个序列化器再返回——返回类型就不对。
     * 另外这个 lambda 会在容器启动、构建序列化器时被调用一次，
     * 它不是「每个请求执行一次」，所以里面的逻辑不要依赖请求上下文。
     *
     * @return 定制器，Spring Boot 会在构建容器内那个序列化器时自动收集并应用它；
     *         调用方不需要、也不应该手动执行它
     */
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder
                // 1. 保留 null 字段：BaseResponse 的 data 即使为 null 也必须出现在 JSON 中，
                //    否则前端统一拦截器读不到 data 字段
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                // 2. 注册 Long 与时间类型的序列化规则
                .addModule(jsonTypeModule());
    }

    /**
     * 组装「Long 转字符串 + 时间格式统一」这两个类型规则
     *
     * <p>为什么需要它：Jackson 对 Long 的默认写法是裸数字，对 LocalDateTime 的默认写法是
     * ISO-8601（中间带一个 T）。前者会在前端丢精度，后者与前端约定的展示格式不一致，
     * 两件事都必须在服务端一次性解决，否则每个接口都要各自处理一遍。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>创建模块并命名：名字会出现在异常信息里，出问题时能一眼看出是哪个模块的规则；</li>
     *     <li>注册 Long 的序列化器：包装类型 Long 与基本类型 long 各注册一次。
     *     这两者在 Jackson 眼里是两个不同的类型，只注册一个的话，
     *     另一种写法的字段仍然输出裸数字——症状是「有的 id 是字符串、有的是数字」，
     *     前端拿到的类型不稳定，比全都错还难查；</li>
     *     <li>注册 LocalDateTime 的序列化与反序列化：输出用固定格式，输入用宽松解析器；</li>
     *     <li>返回模块，交给上一步的定制器挂到全局序列化器上。</li>
     * </ol>
     *
     * <p>容易踩的坑：只注册序列化器是不够的——反序列化方向如果不注册，
     * 前端传回 "2026-09-27 10:30:05" 这种格式时会解析失败。
     * 反过来，Long 的反序列化不需要注册：Jackson 本来就能把字符串 "123" 转回 Long，
     * 所以「Long 序列化成字符串」这件事是单向的，别顺手多写一个反序列化器。
     *
     * @return 只含本项目两条类型规则的模块；每次调用都会新建一个实例，
     *         因此只在构建序列化器时调用一次，不要缓存后跨容器复用
     */
    private SimpleModule jsonTypeModule() {
        SimpleModule module = new SimpleModule("runshi-json-module");

        // 雪花算法生成的 19 位 Long 主键超出了 JS Number 的安全整数范围（2^53-1），
        // 以数字形式返回会被前端截断（末位变 0），因此统一序列化为字符串。
        // 反序列化无需额外注册：Jackson 会自动把 "1945678901234567890" 转回 Long
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // LocalDateTime 默认输出 ISO-8601（形如 2026-09-27T10:30:05），与前端约定的格式不一致
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT_DATE_TIME_PARSER));

        // LocalDate 默认就是 yyyy-MM-dd，与约定一致，无需重复配置
        return module;
    }
}

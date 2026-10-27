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
 * 必须用 Customizer 而不是自己 new 一个 ObjectMapper：Spring MVC 写 JSON 时用的是容器里
 * 由 Boot 建好的那个实例。自己 new 一个再注册成 Bean，效果是「容器里多了一个没人用的对象」——
 * 输出一个字符都不会变，而且完全不报错。
 *
 * 两条全局规则：null 字段照样输出（前端拦截器要能读到 data 字段）；
 * Long 输出成字符串、LocalDateTime 输出成 yyyy-MM-dd HH:mm:ss。
 *
 * @author shaoshing
 */
@Configuration
public class JsonConfig {

    /**
     * 统一的时间输出格式，避免前端再对 ISO-8601 的 "T" 做二次处理
     *
     * 包级可见，供 RedisConfig 复用，保证缓存与接口的时间格式一致。
     * 用 static final 是因为 DateTimeFormatter 不可变且线程安全，建一次就能全局复用。
     */
    static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 时间反序列化格式：同时兼容 "2026-09-27 10:30:05" 与 ISO-8601 的 "2026-09-27T10:30:05"（含毫秒）
     *
     * 解析宽松、输出严格：输出格式由我们单方面决定，越统一越好；而输入来自各种客户端，
     * 收得越紧，越容易在「格式不对」上浪费排查时间，而这个错误对用户毫无意义。
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
     * 定制而不是替换，框架自己的默认配置全部保留，升级时也不会因为手搓了一个实例
     * 而错过新版本的默认行为。
     *
     * 坑：改字段包含策略必须放在注册模块之前，它改的是序列化器的全局配置而不是模块内的规则。
     * 另外这个 lambda 只在容器启动、构建序列化器时执行一次，不是每个请求执行一次。
     *
     * @return 定制器，Spring Boot 在构建容器内那个序列化器时自动收集并应用
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
     * Long 必须转字符串：雪花 ID 是 19 位，超出 JS Number 的安全整数范围，
     * 以数字返回会被前端截断（末位变 0）。
     *
     * 坑一：Long 与 long 各注册一次。它们在 Jackson 眼里是两个类型，只注册一个的话
     * 另一种写法仍输出裸数字，症状是「有的 id 是字符串、有的是数字」，比全都错还难查。
     * 坑二：反序列化方向必须注册 LocalDateTime，否则前端传回约定格式会解析失败；
     * 而 Long 的反序列化不需要注册，Jackson 本来就能把字符串转回 Long。
     *
     * @return 只含本项目两条类型规则的模块，每次调用都新建实例
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

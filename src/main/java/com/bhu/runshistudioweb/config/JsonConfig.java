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
 * 全局 JSON 序列化与反序列化配置。
 *
 * author: shaoshing
 *
 * <p>为什么用 Customizer 而不自己 new 一个 ObjectMapper：Spring MVC 的 HttpMessageConverter
 * 使用的是 Spring 容器里的 JsonMapper，自己 new 出来的实例不会被使用，只有注册 Customizer
 * 才能真正改写全局序列化行为。
 */
@Configuration
public class JsonConfig {

    /**
     * 统一的时间输出格式，避免前端再对 ISO-8601 的 "T" 做二次处理。
     * 包级可见，供 RedisConfig 复用，保证缓存与接口的时间格式一致
     */
    static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 时间反序列化格式：同时兼容 "2026-09-27 10:30:05" 与 ISO-8601 的 "2026-09-27T10:30:05"（含毫秒）。
     * 只写死其中一种，前端按另一种格式传参时会直接抛 InvalidFormatException。
     */
    static final DateTimeFormatter LENIENT_DATE_TIME_PARSER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    /**
     * 定制全局 JsonMapper（Spring Boot 4 的 Jackson 3 定制入口）
     *
     * @return Customizer，Spring Boot 会自动收集并应用到容器中的 JsonMapper
     */
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder
                // 1. 保留 null 字段：BaseResponse 的 data 即使为 null 也必须出现在 JSON 中，
                //    否则前端统一拦截器读不到 data 字段（见 BaseResponseTest#errorSerializesDataField）
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                // 2. 注册 Long 与时间类型的序列化规则
                .addModule(jsonTypeModule());
    }

    /**
     * JSON 类型转换模块：Long 转字符串 + 时间格式统一
     */
    private SimpleModule jsonTypeModule() {
        SimpleModule module = new SimpleModule("runshi-json-module");

        // 雪花算法生成的 19 位 Long 主键超出了 JS Number 的安全整数范围（2^53-1），
        // 以数字形式返回会被前端截断（末位变 0），因此统一序列化为字符串。
        // 反序列化无需额外注册：Jackson 会自动把 "1945678901234567890" 转回 Long
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // LocalDateTime 默认输出 ISO-8601（2026-09-27T10:30:05），与前端约定格式不一致
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT_DATE_TIME_PARSER));

        // LocalDate 默认就是 yyyy-MM-dd，与约定一致，无需重复配置
        return module;
    }
}

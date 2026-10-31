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
 * @author shaoshing
 */
@Configuration
public class JsonConfig {

    /** 统一的时间输出格式，避免前端再对 ISO-8601 的 "T" 做二次处理 */
    static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 时间反序列化格式：同时兼容 "2026-09-27 10:30:05" 与 ISO-8601 的 "2026-09-27T10:30:05"（含毫秒） */
    static final DateTimeFormatter LENIENT_DATE_TIME_PARSER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    /**
     * 定制全局 JSON 序列化器：让 null 字段保留、Long 变字符串、时间格式化
     *
     * @return 定制器，Spring Boot 在构建容器内那个序列化器时自动收集并应用
     */
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> builder
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                // 2. 注册 Long 与时间类型的序列化规则
                .addModule(jsonTypeModule());
    }

    /** 组装「Long 转字符串 + 时间格式统一」这两个类型规则 */
    private SimpleModule jsonTypeModule() {
        SimpleModule module = new SimpleModule("runshi-json-module");

        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);

        // LocalDateTime 默认输出 ISO-8601（形如 2026-09-27T10:30:05），与前端约定的格式不一致
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT_DATE_TIME_PARSER));

        // LocalDate 默认就是 yyyy-MM-dd，与约定一致，无需重复配置
        return module;
    }
}

package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词「文件 → 字段」加载链路的单元测试
 *
 * author: shaoshing
 *
 * <p>为什么不写成 {@code @SpringBootTest}：这条链路只依赖
 * {@link AiProperties}（提供位置）+ {@code ResourceLoader}（读文件）两个依赖，
 * 直接构造 {@link AiManager} 就能覆盖「classpath / file: / 文件缺失 / BOM」四种情形，
 * 没必要为它单独启动一个 Spring 上下文（本类方法在 base-url 为空时只加载提示词、
 * 不会构建模型、不会发网络请求）。
 *
 * <p>「文件 → 模型」的全链路由 {@code AiChatCrudTest} 覆盖（它把 location 指向测试夹具，
 * 并断言模型收到的首条消息就是夹具内容）；文件缺失时的降级由 {@code AiPromptFallbackTest} 覆盖。
 */
class AiPromptLoadTest {

    /** 构造只装「配置 + 资源加载器」的 AiManager，并触发启动加载 */
    private AiManager managerWith(String location) {
        AiProperties properties = new AiProperties();
        properties.setSystemPromptLocation(location);
        AiManager manager = new AiManager();
        ReflectionTestUtils.setField(manager, "aiProperties", properties);
        ReflectionTestUtils.setField(manager, "resourceLoader", new DefaultResourceLoader());
        manager.initModels();
        return manager;
    }

    private String loadedPrompt(AiManager manager) {
        return (String) ReflectionTestUtils.getField(manager, "systemPrompt");
    }

    @Test
    @DisplayName("classpath 默认位置：加载到线上提示词文件（无 BOM、无首尾空白）")
    void classpathDefaultLoadsRealPromptFile() {
        // 用 AiProperties 的默认值（classpath:prompts/system-prompt.txt），与线上配置一致
        String location = new AiProperties().getSystemPromptLocation();
        String prompt = loadedPrompt(managerWith(location));

        assertNotNull(prompt);
        assertTrue(prompt.length() > 200, "线上提示词应有实质内容，实际长度 " + prompt.length());
        assertTrue(prompt.contains("润石工作室"), "应包含角色设定：" + prompt);
        assertFalse(prompt.startsWith("\uFEFF"), "BOM 必须被剥掉");
        assertEquals(prompt.strip(), prompt, "首尾空白必须被去掉");
    }

    @Test
    @DisplayName("file: 前缀：从服务器文件加载（部署期不改包换话术的方式）")
    void filePrefixLoadsExternalFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("system-prompt.txt");
        Files.writeString(file, "你是测试宣言\n第二行\n", StandardCharsets.UTF_8);

        String prompt = loadedPrompt(managerWith("file:" + file.toAbsolutePath()));

        // 首尾空白被 strip，内部换行保留
        assertEquals("你是测试宣言\n第二行", prompt);
    }

    @Test
    @DisplayName("文件缺失：不抛异常、提示词为空（聊天仍可用），由启动日志告警兜底")
    void missingFileBlanksPromptInsteadOfFailing() {
        String prompt = loadedPrompt(managerWith("classpath:prompts/no-such-prompt-xyz.txt"));
        assertEquals("", prompt);
    }

    @Test
    @DisplayName("BOM 文件：只剥离 BOM 字符，正文原样保留")
    void bomPrefixedFileIsStripped(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("bom-prompt.txt");
        Files.writeString(file, "\uFEFF你好，提示词", StandardCharsets.UTF_8);

        assertEquals("你好，提示词", loadedPrompt(managerWith("file:" + file.toAbsolutePath())));
    }
}
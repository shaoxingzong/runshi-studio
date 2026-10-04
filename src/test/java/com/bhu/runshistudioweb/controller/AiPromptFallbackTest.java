package com.bhu.runshistudioweb.controller;

import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 系统提示词文件缺失时的降级验收测试
 *
 * author: shaoshing
 *
 * <p>与「AI 不配也能启动」同一条纪律的实证：location 指向一个不存在的文件时，
 * 应用必须<b>照常启动</b>（启动日志 warn），聊天接口<b>照常可用</b>，
 * 只是不带 system 消息——少一句身份约束，不该让整个功能不可用。
 *
 * <p>断言落点放在 Stub 收到的请求体上（messages 只有一条 user），
 * 因为「没有 system 消息」这件事在响应里看不出来，只有模型侧能证明。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiPromptFallbackTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private JsonMapper jsonMapper;

    private static HttpServer stub;

    /** 最近一次收到的请求体（用于断言消息组装） */
    private static volatile String lastRequestBody;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"降级-回答\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        stub.start();
    }

    @DynamicPropertySource
    static void stubProps(DynamicPropertyRegistry registry) {
        int port = stub.getAddress().getPort();
        registry.add("studio.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("studio.ai.api-key", () -> "test-key");
        registry.add("studio.ai.model", () -> "test-model");
        // 刻意指向不存在的文件：验证「启动不失败 + 聊天不带 system 消息」的降级链路
        registry.add("studio.ai.system-prompt-location",
                () -> "classpath:prompts/no-such-file-for-fallback.txt");
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @Test
    @DisplayName("提示词文件缺失：聊天仍可用，且请求里没有 system 消息（首条即 user）")
    void missingPromptFileStillChatsWithoutSystemMessage() throws Exception {
        String body = mockMvc.perform(post("/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"T25-降级提问\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("\"code\":\"00000\""), "聊天应照常成功：" + body);
        assertTrue(body.contains("降级-回答"), "应返回 Stub 的回答：" + body);

        assertNotNull(lastRequestBody, "Stub 应收到请求");
        JsonNode messages = jsonMapper.readTree(lastRequestBody).get("messages");
        // 无历史、无 system：只应有本次提问一条消息
        assertEquals(1, messages.size(), "不应凭空产生 system 消息：" + lastRequestBody);
        assertEquals("user", messages.get(0).get("role").asString(), "首条消息应为本次提问：" + lastRequestBody);
        assertEquals("T25-降级提问", messages.get(0).get("content").asString());
    }
}
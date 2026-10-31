package com.bhu.runshistudioweb.controller;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 文件上传接口验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileControllerTest {

    /** 合法 PNG 的头部字节 + 填充（校验只读前 12 字节） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 1, 2, 3, 4
    };

    @Resource
    private MockMvc mockMvc;

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** 上传根目录：surefire 注入的 FILE_ROOT_DIR（见 pom.xml），与被测代码读的是同一个值 */
    private String testUploadRoot() {
        return System.getProperty("FILE_ROOT_DIR");
    }

    @Test
    @DisplayName("安全：上传接口必须登录后才能调用（不在白名单内）")
    void uploadRequiresLogin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", PNG_BYTES);

        String response = body(mockMvc.perform(multipart("/file/upload").file(file)).andReturn());

        assertTrue(response.contains("A0201"), "匿名上传应被拦截（未登录 A0201），实际响应：" + response);
    }

    @Test
    @DisplayName("完整链路：登录后上传成功，返回的图片 URL 游客也能直接访问")
    void uploadThenAnonymousAccess() throws Exception {
        String account = "file" + (System.nanoTime() % 100000);
        String registerJson = "{\"userAccount\":\"" + account
                + "\",\"userName\":\"文件测试\",\"userPassword\":\"Studio@2026\",\"checkPassword\":\"Studio@2026\"}";
        mockMvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON).content(registerJson));

        String loginBody = body(mockMvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"Studio@2026\"}")).andReturn());
        String token = loginBody.substring(loginBody.indexOf("\"token\":\"") + 9);
        token = token.substring(0, token.indexOf('"'));

        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", PNG_BYTES);
        String uploadBody = body(mockMvc.perform(multipart("/file/upload").file(file)
                .header("satoken", token)).andReturn());

        assertTrue(uploadBody.contains("\"code\":\"00000\""), "上传失败：" + uploadBody);
        String accessPath = uploadBody.substring(uploadBody.indexOf("\"/uploads/") + 1);
        accessPath = accessPath.substring(0, accessPath.indexOf('"'));

        // 关键：不带任何 token 去取这张图，必须拿得到字节（否则官网游客看到的全是裂图）
        MvcResult imageResult = mockMvc.perform(get(accessPath)).andReturn();
        assertEquals(200, imageResult.getResponse().getStatus(), "匿名访问上传的图片失败：" + accessPath);
        assertArrayEquals(PNG_BYTES, imageResult.getResponse().getContentAsByteArray(), "返回的图片内容不一致");

        // 清理测试产生的文件
        Path stored = Paths.get(testUploadRoot(),
                accessPath.substring("/uploads/".length()));
        Files.deleteIfExists(stored);
    }
}

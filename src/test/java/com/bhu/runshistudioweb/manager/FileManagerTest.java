package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件存储管理器验收测试
 *
 * author: shaoshing
 *
 * <p>这组用例的重点不是「能上传成功」，而是**不该通过的必须被拦住**：
 * 伪装后缀的文本文件、空文件、超短文件、删除时的路径穿越。
 * 文件上传是最容易被拿来做攻击入口的功能，这些边界一个都不能少。
 *
 * <p>隔离策略：用 {@code properties} 把存储根目录指到系统临时目录，
 * 绝不在真实的上传目录里造垃圾文件（否则本机开发时目录很快会被测试数据淹没）。
 */
// 上传根目录由 surefire 的 FILE_ROOT_DIR 统一注入（见 pom.xml），这里保持默认配置以便与其它测试共用同一个容器
@SpringBootTest
class FileManagerTest {

    @Resource
    private FileManager fileManager;

    /** 本次用例创建的文件，用完即删 */
    private final List<Path> created = new ArrayList<>();

    /** 造一个「头部合法 + 填充字节」的假图片：校验只读前 12 字节，因此不需要真实图片数据 */
    private byte[] fakeImage(byte[] magic, int totalLength) {
        byte[] bytes = new byte[Math.max(totalLength, 16)];
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        for (int i = magic.length; i < bytes.length; i++) {
            bytes[i] = (byte) (i & 0xFF);
        }
        return bytes;
    }

    private byte[] pngBytes() {
        return fakeImage(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 24);
    }

    private byte[] jpegBytes() {
        return fakeImage(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 24);
    }

    /** 上传根目录：surefire 注入的 FILE_ROOT_DIR（见 pom.xml），与被测代码读的是同一个值 */
    private String testUploadRoot() {
        return System.getProperty("FILE_ROOT_DIR");
    }

    /** 上传并记录路径，便于用例结束清理 */
    private String upload(MultipartFile file) {
        String accessPath = fileManager.upload(file);
        created.add(Paths.get(testUploadRoot(),
                accessPath.substring("/uploads/".length())));
        return accessPath;
    }

    private void cleanup() {
        for (Path path : created) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 清理失败不影响用例结论（临时目录由系统回收）
            }
        }
        created.clear();
    }

    @Test
    @DisplayName("合法 PNG：返回规范化路径，且文件真实落盘、内容与上传一致")
    void uploadPng() throws Exception {
        byte[] bytes = pngBytes();

        String accessPath = upload(new MockMultipartFile("file", "头像.png", "image/png", bytes));

        // 路径形如 /uploads/2026/09/32位hex.png
        assertTrue(accessPath.matches("^/uploads/\\d{4}/\\d{2}/[0-9a-f]{32}\\.png$"),
                "返回路径不符合约定：" + accessPath);

        Path stored = Paths.get(testUploadRoot(),
                accessPath.substring("/uploads/".length()));
        assertTrue(Files.exists(stored), "文件没有真正落盘：" + stored);
        assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(stored)), "落盘内容与上传内容不一致");

        cleanup();
    }

    @Test
    @DisplayName("合法 JPEG：扩展名由魔数决定，输出 .jpg")
    void uploadJpeg() {
        String accessPath = upload(new MockMultipartFile("file", "photo.jpeg", "image/jpeg", jpegBytes()));

        assertTrue(accessPath.endsWith(".jpg"), "JPEG 应存为 .jpg，实际：" + accessPath);

        cleanup();
    }

    @Test
    @DisplayName("安全：把文本文件改名成 .png 上传，必须被魔数校验拦下（不信任后缀）")
    void textFileRenamedToPngIsRejected() {
        byte[] text = "这不是图片，只是普通文本内容，长度足够".getBytes();

        BusinessException ex = assertThrows(BusinessException.class, () -> upload(
                new MockMultipartFile("file", "evil.png", "image/png", text)));

        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("不支持的文件格式"), "提示语应说明是格式问题：" + ex.getMessage());
    }

    @Test
    @DisplayName("安全：空文件与不足 12 字节的文件都必须被拒绝")
    void emptyAndTooShortFileRejected() {
        assertThrows(BusinessException.class, () -> upload(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])));

        assertThrows(BusinessException.class, () -> upload(
                new MockMultipartFile("file", "short.png", "image/png", new byte[]{1, 2, 3, 4, 5})));
    }

    @Test
    @DisplayName("删除：本系统上传的文件可以删掉")
    void deleteUploadedFile() {
        String accessPath = upload(new MockMultipartFile("file", "a.png", "image/png", pngBytes()));
        Path stored = Paths.get(testUploadRoot(),
                accessPath.substring("/uploads/".length()));
        assertTrue(Files.exists(stored));

        assertTrue(fileManager.delete(accessPath), "删除上传目录内的文件应成功");
        assertFalse(Files.exists(stored), "文件仍然存在");
    }

    @Test
    @DisplayName("安全：删除接口必须挡住路径穿越，绝不能删到上传目录之外")
    void deleteRejectsPathTraversal() {
        // 前缀不是 /uploads/ 一律拒绝（传绝对路径也不行）
        assertFalse(fileManager.delete(null));
        assertFalse(fileManager.delete("C:/Windows/win.ini"));
        assertFalse(fileManager.delete("/data/uploads/x.png"));

        // 带 .. 的穿越路径：normalize 后已不在存储根目录内，必须返回 false
        assertFalse(fileManager.delete("/uploads/../../windows/win.ini"));
        assertFalse(fileManager.delete("/uploads/2026/09/../../../../boot.ini"));

        // 真实存在的系统文件没被误删（作为兜底断言）
        assertTrue(Files.exists(Paths.get("C:/Windows")), "系统目录不应被动过");
    }
}

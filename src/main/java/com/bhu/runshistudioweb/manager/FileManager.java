package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 通用文件存储管理器（Manager 层：只做技术能力，不含业务规则）
 *
 * 返回相对访问路径，由 WebMvcConfig 把 /uploads/** 映射到同一个根目录，两者必须是同一个配置项。
 *
 * @author shaoshing
 */
@Component
@RequiredArgsConstructor
public class FileManager {

    /** 上传文件存储根目录，对应配置项 studio.file.root-dir */
    @Value("${studio.file.root-dir}")
    private final String rootDir;

    /**
     * 文件上传：校验 → 落盘 → 返回相对访问路径
     *
     * @param file 接口接收到的文件，可为 null（为 null 时抛 A0401）；只接受 PNG 与 JPEG，真实类型由文件头字节判定，与文件名后缀无关
     * @return 相对访问路径，形如 /uploads/2026/09/{uuid}.png
     * @throws com.bhu.runshistudioweb.exception.BusinessException 文件为空、内容不足 12 字节、格式不在白名单内、路径不合法时抛 A0401；读失败抛 A0401，写失败抛 B0001
     */
    public String upload(MultipartFile file) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");

        byte[] head = new byte[12];
        int bytesRead;
        try (InputStream inputStream = file.getInputStream()) {
            bytesRead = inputStream.read(head, 0, 12);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "读取文件失败");
        }
        ThrowUtils.throwIf(bytesRead < 12, ErrorCode.PARAMS_ERROR, "文件内容损坏或非法");

        String extension = detectImageType(head);
        ThrowUtils.throwIf(extension == null, ErrorCode.PARAMS_ERROR, "不支持的文件格式，仅允许上传 PNG 或 JPEG 图片");

        String fileName = UUID.randomUUID().toString().replace("-", "") + extension;

        String dateSubDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));

        Path baseDirPath = Paths.get(rootDir).toAbsolutePath().normalize();
        Path targetDirPath = baseDirPath.resolve(dateSubDir).normalize();
        Path targetFilePath = targetDirPath.resolve(fileName).normalize();

        ThrowUtils.throwIf(!targetFilePath.startsWith(baseDirPath), ErrorCode.PARAMS_ERROR, "检测到非法路径");

        try {
            Files.createDirectories(targetDirPath);
            // 重新打开一次流：上面读头部时已经关闭了那个流，输入流不能复用
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件落盘失败");
        }

        return String.format("/uploads/%s/%s", dateSubDir, fileName);
    }

    /**
     * 按相对访问路径删除文件
     *
     * @param accessPath 相对访问路径（如 /uploads/2026/09/xxx.png）；为 null 或不是 /uploads/ 开头时返回 false
     * @return true 表示文件确实被删掉了；路径非法、越权、文件不存在或出错都返回 false
     */
    public boolean delete(String accessPath) {
        if (accessPath == null || !accessPath.startsWith("/uploads/")) {
            return false;
        }
        try {
            // 步骤 2：剥离前缀 /uploads/ 得到子路径，再拼成绝对路径并消解 . 与 ..
            String relativePath = accessPath.substring("/uploads/".length());
            Path baseDirPath = Paths.get(rootDir).toAbsolutePath().normalize();
            Path targetFilePath = baseDirPath.resolve(relativePath).normalize();

            if (!targetFilePath.startsWith(baseDirPath)) {
                return false;
            }
            // deleteIfExists：文件不存在不算错误，返回 false 即可（重复删除是常见场景）
            return Files.deleteIfExists(targetFilePath);
        } catch (Exception e) {
            return false;
        }
    }

    /** 魔数识别文件真实类型（比对文件头字节，不信任文件名后缀） */
    private String detectImageType(byte[] head) {
        // 先判长度：不足 12 字节连比对都不必做，直接判为无法识别
        if (head == null || head.length < 12) {
            return null;
        }

        byte[] pngMagic = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (matchMagic(head, pngMagic)) {
            return ".png";
        }

        byte[] jpegMagic = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        if (matchMagic(head, jpegMagic)) {
            return ".jpg";
        }

        // 都不命中返回 null，由调用方判成「格式不支持」
        return null;
    }

    /** 比对源字节数组是否以指定魔数开头 */
    private boolean matchMagic(byte[] source, byte[] magic) {
        // 按下标逐字节比对，一旦不等立刻返回 false
        for (int i = 0; i < magic.length; i++) {
            if (source[i] != magic[i]) {
                return false;
            }
        }
        // 全部相等返回 true
        return true;
    }
}

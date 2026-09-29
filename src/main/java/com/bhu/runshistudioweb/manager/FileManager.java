package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.exception.BusinessException;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import org.springframework.beans.factory.annotation.Value;
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
 * 通用文件存储管理器 (Manager 层)
 *
 * author: shaoshing
 *
 * <p>为什么单独放一层 Manager：它只依赖 {@code MultipartFile} 与本地磁盘，**不认识 HTTP**，
 * 因此既可以被 Controller 调用，也能被定时任务、数据迁移脚本复用，还能直接写单测。
 *
 * <p>整体思路：**先校验、后落盘**。所有校验（是否为空、真实类型、路径合法性）都在写磁盘之前完成，
 * 避免出现「先写了脏文件再判断不合法、还要回头删」的中间状态。
 *
 * <p>三重安全防线（缺一不可，任何一层单独看都不够）：
 * <ol>
 *     <li><b>魔数校验</b>：只看文件头字节判断真实类型，绝不信任文件名后缀
 *     （把木马改名成 .png 是最基础的绕过手段）；</li>
 *     <li><b>重命名落盘</b>：文件名用 UUID 重新生成，丢弃用户传入的原始文件名，
 *     从根本上消除 {@code ../../} 穿越与「同目录覆盖」问题；</li>
 *     <li><b>路径规范化校验</b>：落盘前用 normalize + startsWith 再确认一次目标路径在存储根目录内，
 *     这是纵深防御——即使前面的规则将来被改坏，也不会写到根目录之外。</li>
 * </ol>
 *
 * <p>存储目录结构：{@code {root-dir}/yyyy/MM/{uuid}.{ext}}，按月分目录是为了避免单目录文件过多
 * （NTFS 下同一目录文件上万后，列举与删除都会明显变慢）。
 */
@Component
public class FileManager {

    /**
     * 上传文件存储根目录，对应配置项 {@code studio.file.root-dir}
     * （与 {@code WebMvcConfig} 中的静态资源映射必须是同一个值，否则文件存得进去、却访问不到）
     */
    @Value("${studio.file.root-dir}")
    private String rootDir;

    /**
     * 文件上传 (校验 -> 落盘 -> 返回相对访问路径)
     *
     * @param file 接口接收的文件
     * @return 相对访问路径 (例如 /uploads/2026/09/{uuid}.png)
     */
    public String upload(MultipartFile file) {
        // 步骤 1：判空
        // 注意 isEmpty() 只能作为快速失败，它依据的是 Content-Length，可以被伪造，
        // 因此后面还必须用「真实读出的字节数」再判断一次
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");

        // 步骤 2：读前 12 字节头部信息 (严禁将全量文件加载进内存)
        // 用 try-with-resources 保证流被关闭；只读 12 字节意味着哪怕上传 1GB 文件，
        // 校验阶段的内存占用也只有 12 字节——这是防止 OOM 攻击的关键
        byte[] head = new byte[12];
        int bytesRead;
        try (InputStream inputStream = file.getInputStream()) {
            bytesRead = inputStream.read(head, 0, 12);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "读取文件失败");
        }
        // 不足 12 字节的文件直接拒绝：正常图片（哪怕 1x1 像素）的头部都远超 12 字节，
        // 这里同时挡住了空文件与「只传几个字节」的探测请求
        ThrowUtils.throwIf(bytesRead < 12, ErrorCode.PARAMS_ERROR, "文件内容损坏或非法");

        // 步骤 3：魔数白名单校验 (此时磁盘尚未发生任何写入)
        // 采用「白名单」而非「黑名单」：黑名单永远列不全（.jsp/.php/.svg 带脚本、大小写变形等），
        // 白名单则天然只放行已知安全的类型
        String extension = detectImageType(head);
        ThrowUtils.throwIf(extension == null, ErrorCode.PARAMS_ERROR, "不支持的文件格式，仅允许上传 PNG 或 JPEG 图片");

        // 步骤 4：生成文件名 (UUID + 校验得出的真实扩展名，绝不信任原始文件名)
        // 两个要点：① 扩展名来自魔数识别结果，不是 getOriginalFilename()；
        //          ② 32 位 UUID 保证文件名不可猜、不会重名（避免并发上传互相覆盖）
        String fileName = UUID.randomUUID().toString().replace("-", "") + extension;

        // 步骤 5：按年月生成相对子目录 (如 2026/09)
        // 用固定格式的日期而非用户输入，避免路径里混入非法字符
        String dateSubDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));

        // 步骤 6：拼接路径与路径穿越 (Path Traversal) 防御
        // toAbsolutePath() 把相对配置（如 ./data/uploads）转成绝对路径，避免依赖运行时工作目录；
        // normalize() 消解掉路径里的 "." 与 ".."，让后续的边界判断无法被绕过
        Path baseDirPath = Paths.get(rootDir).toAbsolutePath().normalize();
        Path targetDirPath = baseDirPath.resolve(dateSubDir).normalize();
        Path targetFilePath = targetDirPath.resolve(fileName).normalize();

        // 纵深防御：确认目标路径绝对在存储根目录内
        ThrowUtils.throwIf(!targetFilePath.startsWith(baseDirPath), ErrorCode.PARAMS_ERROR, "检测到非法路径");

        // 步骤 7：创建目录并落盘存储
        // createDirectories 会创建多级不存在的目录（本月第一次上传时 yyyy/MM 往往还不存在）；
        // REPLACE_EXISTING 在这里只是兜底，实际文件名是 UUID，不会重名
        try {
            Files.createDirectories(targetDirPath);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 磁盘满、无写权限等问题在这里暴露。注意不要把 e.getMessage() 返回给前端
            // （可能带出服务器绝对路径），细节交给日志
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件落盘失败");
        }

        // 步骤 8：返回相对访问路径
        // 只返回相对路径，不返回 http://host:port 的绝对地址：
        // 域名会随环境（本地/测试/生产）变化，由前端拼接才不会把地址写死
        return String.format("/uploads/%s/%s", dateSubDir, fileName);
    }

    /**
     * 按相对访问路径删除文件
     *
     * @param accessPath 相对访问路径 (如 /uploads/2026/09/xxx.png)
     * @return 是否成功删除
     */
    public boolean delete(String accessPath) {
        // 前缀白名单：只允许删除本系统上传目录下的文件。
        // 少了这一层，传入绝对路径（如 C:/Windows/win.ini）就可能删除服务器任意文件
        if (accessPath == null || !accessPath.startsWith("/uploads/")) {
            return false;
        }
        try {
            // 剥离前缀 /uploads/ 得到子路径
            String relativePath = accessPath.substring("/uploads/".length());
            Path baseDirPath = Paths.get(rootDir).toAbsolutePath().normalize();
            Path targetFilePath = baseDirPath.resolve(relativePath).normalize();

            // 越权与路径穿越防御：normalize 之后仍要再确认一次边界，
            // 否则 "/uploads/../../xxx" 这类路径会指到根目录之外
            if (!targetFilePath.startsWith(baseDirPath)) {
                return false;
            }
            return Files.deleteIfExists(targetFilePath);
        } catch (Exception e) {
            // 删除失败不向上抛异常：删除通常发生在「换头像」这类主流程的收尾阶段，
            // 因删旧文件失败而让整个业务失败，是本末倒置；留下一条日志即可
            return false;
        }
    }

    /**
     * 魔数识别文件真实类型 (16 进制头部字节比对)
     *
     * @param head 文件头字节（至少 12 字节）
     * @return 识别出的扩展名（.png / .jpg），无法识别时返回 null
     */
    private String detectImageType(byte[] head) {
        if (head == null || head.length < 12) {
            return null;
        }

        // PNG 魔数: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngMagic = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (matchMagic(head, pngMagic)) {
            return ".png";
        }

        // JPEG/JPG 魔数: FF D8 FF
        // 只比对 3 个字节即可：其后可能是 JFIF(E0)、EXIF(E1) 等不同段，
        // 若强行匹配到第 4 字节，会把一批合法 JPEG（如相机直出照片）误判为非法
        byte[] jpegMagic = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        if (matchMagic(head, jpegMagic)) {
            return ".jpg";
        }

        return null;
    }

    /**
     * 比对源字节数组是否以指定魔数开头
     *
     * @param source 文件头字节
     * @param magic  魔数
     * @return 完全匹配返回 true
     */
    private boolean matchMagic(byte[] source, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (source[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}

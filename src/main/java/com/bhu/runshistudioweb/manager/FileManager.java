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
 * <p>为什么单独放一层 Manager：它只依赖 MultipartFile 与本地磁盘，不认识 HTTP，
 * 因此既可以被 Controller 调用，也能被定时任务、数据迁移脚本复用，还能直接写单测
 * （不必起 Spring MVC、不必造请求对象）。Controller 那边只负责接参与返回，
 * 一旦把校验与落盘写在 Controller 里，这份逻辑就再也无法被非 Web 入口复用了。
 *
 * <p>整体思路：先校验、后落盘。所有校验（是否为空、真实类型、路径合法性）都在写磁盘之前完成，
 * 避免出现「先写了脏文件再判断不合法、还要回头删」的中间状态——
 * 那种写法一旦在删除这一步失败，脏文件就永久留在磁盘上，而且没人知道。
 *
 * <p>三重安全防线（缺一不可，任何一层单独看都不够）：
 * <ul>
 *     <li>魔数校验：魔数是文件开头那几个固定字节，只看它判断真实类型，绝不信任文件名后缀
 *     （把木马改名成 .png 是最基础的绕过手段，后缀完全由上传者控制）；</li>
 *     <li>重命名落盘：文件名用 UUID 重新生成，丢弃用户传入的原始文件名，
 *     从根本上消除 ../../ 穿越与「同目录覆盖」问题；</li>
 *     <li>路径规范化校验：落盘前用 normalize + startsWith 再确认一次目标路径在存储根目录内，
 *     这是纵深防御——即使前面的规则将来被改坏，也不会写到根目录之外。</li>
 * </ul>
 *
 * <p>存储目录结构：{root-dir}/yyyy/MM/{uuid}.{ext}，按月分目录是为了避免单目录文件过多
 * （NTFS 下同一目录文件上万后，列举与删除都会明显变慢）。
 * 返回给调用方的是相对访问路径（如 /uploads/2026/09/xxx.png），
 * 由 WebMvcConfig 把 /uploads/** 映射到同一个根目录，两者必须是同一个配置项，
 * 否则会出现「上传成功、图片却 404」——文件确实写进去了，只是写在没人访问的目录里。
 *
 * @author shaoshing
 */
@Component
@RequiredArgsConstructor
public class FileManager {

    /**
     * 上传文件存储根目录，对应配置项 studio.file.root-dir
     *
     * <p>必须与 WebMvcConfig 里 /uploads/** 的静态资源映射指向同一个配置项，
     * 否则文件存得进去、却访问不到：接口返回 200 与一个路径，浏览器请求时 404，
     * 而从上传接口的返回里看不出任何异常。
     *
     * <p>为什么 final 字段也能拿到配置值：项目在 lombok.config 里配置了
     * lombok.copyableAnnotations，把 @Value 复制到 Lombok 生成的构造器参数上，
     * 于是 Spring 创建本 Bean 时能按参数注入。这样既保留了「依赖不可变」（final），
     * 又不用为它手写构造器。若那条 lombok.config 被删掉，
     * 症状是启动时报「无法解析占位符」，而不是运行期空指针。
     */
    @Value("${studio.file.root-dir}")
    private final String rootDir;

    /**
     * 文件上传：校验 → 落盘 → 返回相对访问路径
     *
     * <p>为什么这样设计：文件上传是「用户能往服务器磁盘写东西」的唯一入口，
     * 因此每一步都要假设输入是恶意的。整体顺序是「先把所有校验做完，再动磁盘」——
     * 反过来（先落盘再校验）一旦校验失败，就要回头删文件，
     * 而删除本身可能失败，脏文件会永久留下。
     * 三道校验各有分工，谁也替代不了谁：判空挡住空请求；
     * 读头部字节判断真实类型（不信任文件名后缀，后缀完全由上传者控制）；
     * 路径规范化确认目标在存储根目录内（纵深防御）。
     * 文件名一律用 UUID 重新生成，彻底丢弃用户传入的原始文件名：
     * 原始文件名里可能带 ../、可能是 .jsp、可能与已有文件重名，
     * 只要还用它拼路径，就得把所有情况都想到；不用它，这些问题一次性消失。
     *
     * <p>执行步骤（与代码里的「步骤 1」~「步骤 8」注释一一对应）：
     * <ol>
     *     <li>步骤 1：判空——isEmpty() 只看 Content-Length，可以被伪造，所以它只是快速失败；</li>
     *     <li>步骤 2：只读文件头的 12 个字节——无论上传多大的文件，
     *     校验阶段的内存占用都只有 12 字节；</li>
     *     <li>步骤 3：魔数白名单校验——此时磁盘尚未发生任何写入；</li>
     *     <li>步骤 4：生成文件名，UUID + 魔数识别出的真实扩展名；</li>
     *     <li>步骤 5：按年月生成相对子目录，用固定格式的日期而不是用户输入；</li>
     *     <li>步骤 6：拼接绝对路径并做路径穿越（Path Traversal：用 ../ 跳出目标目录）防御；</li>
     *     <li>步骤 7：创建目录并落盘；</li>
     *     <li>步骤 8：返回相对访问路径，由前端拼上域名与 context-path。</li>
     * </ol>
     *
     * <p>容易踩的坑：步骤 2 不能把整个文件读进 byte[] 再判断——
     * 那等于让调用方决定服务器要分配多少内存，上传一个 1GB 的文件就能把堆撑爆。
     * 另外步骤 8 只返回相对路径，不返回 http://host:port 的绝对地址：
     * 域名会随环境（本地 / 测试 / 生产）变化，写死在数据里会让迁移环境时全部失效。
     *
     * @param file 接口接收到的文件，可为 null（为 null 时抛 A0401）；只接受 PNG 与 JPEG，
     * 真实类型由文件头字节判定，与文件名后缀无关
     * @return 相对访问路径，形如 /uploads/2026/09/{uuid}.png；调用方拼上域名即可展示
     * @throws com.bhu.runshistudioweb.exception.BusinessException 文件为空、内容不足 12 字节、
     * 格式不在白名单内、路径不合法时抛出（A0401）；读取或写入磁盘失败时抛出
     * （读失败 A0401，写失败 B0001）
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
            // 读流失败说明上传的字节流本身有问题（连接中断、编码异常），
            // 属于「请求方给的数据不可用」，用 A0401 而不是系统错误
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "读取文件失败");
        }
        // 不足 12 字节的文件直接拒绝：正常图片（哪怕 1x1 像素）的头部都远超 12 字节，
        // 这里同时挡住了空文件与「只传几个字节」的探测请求。
        // 这一句才是真正的判空——上面 isEmpty() 依据的 Content-Length 可以被伪造
        ThrowUtils.throwIf(bytesRead < 12, ErrorCode.PARAMS_ERROR, "文件内容损坏或非法");

        // 步骤 3：魔数白名单校验 (此时磁盘尚未发生任何写入)
        // 采用「白名单」而非「黑名单」：黑名单永远列不全（.jsp/.php/.svg 带脚本、大小写变形等），
        // 白名单则天然只放行已知安全的类型
        String extension = detectImageType(head);
        ThrowUtils.throwIf(extension == null, ErrorCode.PARAMS_ERROR, "不支持的文件格式，仅允许上传 PNG 或 JPEG 图片");

        // 步骤 4：生成文件名 (UUID + 校验得出的真实扩展名，绝不信任原始文件名)
        // 两个要点：① 扩展名来自魔数识别结果，不是 getOriginalFilename()；
        //          ② 32 位 UUID 保证文件名不可猜、不会重名（避免并发上传互相覆盖）。
        // 去掉 UUID 里的连字符只是为了让文件名短一点、在日志里更好复制，不影响唯一性
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

        // 纵深防御：确认目标路径绝对在存储根目录内。
        // startsWith 是按路径层级比较的，不是字符串前缀比较——所以 /uploads-evil 不会被误判为合法
        ThrowUtils.throwIf(!targetFilePath.startsWith(baseDirPath), ErrorCode.PARAMS_ERROR, "检测到非法路径");

        // 步骤 7：创建目录并落盘存储
        // createDirectories 会创建多级不存在的目录（本月第一次上传时 yyyy/MM 往往还不存在）；
        // REPLACE_EXISTING 在这里只是兜底，实际文件名是 UUID，不会重名
        try {
            Files.createDirectories(targetDirPath);
            // 重新打开一次流：上面读头部时已经关闭了那个流，输入流不能复用
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
     * <p>为什么这样设计：删除入口接收的是「相对访问路径」这种外部输入，
     * 因此它和上传一样必须假设输入是恶意的。两道防线：
     * 前缀白名单只允许删 /uploads/ 下的文件（少了它，传入 C:/Windows/win.ini
     * 这类绝对路径就可能删掉服务器任意文件）；normalize 之后再用 startsWith
     * 确认边界（否则 /uploads/../../xxx 这类路径会指到根目录之外）。
     * 删除失败一律返回 false 而不抛异常：删除通常发生在「换头像」这类主流程的收尾阶段，
     * 因删旧文件失败而让整个业务失败是本末倒置——旧文件多留一份不会有人察觉，
     * 而用户改了头像却提示失败会直接来投诉。
     *
     * <p>执行步骤（与代码里的步骤 1~3 注释一一对应）：
     * <ol>
     *     <li>步骤 1：前缀白名单——不是 /uploads/ 开头的一律拒绝，连磁盘都不碰；</li>
     *     <li>步骤 2：剥离前缀得到子路径，转成绝对路径并 normalize；</li>
     *     <li>步骤 3：再确认目标仍在根目录内，才真正删除。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个返回值表示「文件是否被删掉了」，而不是「操作是否合法」——
     * 文件本来就不存在时 deleteIfExists 返回 false，这与「路径非法」返回 false
     * 在返回值上无法区分。调用方若需要区分，应自己先判断文件是否存在，
     * 而不是靠这个布尔值反推原因。
     *
     * @param accessPath 相对访问路径（如 /uploads/2026/09/xxx.png），
     * 必须以上传时返回的格式为准；为 null 或不是 /uploads/ 开头时直接返回 false
     * @return true 表示文件确实被删掉了；路径非法、越权、文件不存在或删除过程中出错
     * 都返回 false（本方法不抛异常）
     */
    public boolean delete(String accessPath) {
        // 步骤 1：前缀白名单——只允许删除本系统上传目录下的文件。
        // 少了这一层，传入绝对路径（如 C:/Windows/win.ini）就可能删除服务器任意文件
        if (accessPath == null || !accessPath.startsWith("/uploads/")) {
            return false;
        }
        try {
            // 步骤 2：剥离前缀 /uploads/ 得到子路径，再拼成绝对路径并消解 . 与 ..
            String relativePath = accessPath.substring("/uploads/".length());
            Path baseDirPath = Paths.get(rootDir).toAbsolutePath().normalize();
            Path targetFilePath = baseDirPath.resolve(relativePath).normalize();

            // 步骤 3：越权与路径穿越防御——normalize 之后仍要再确认一次边界，
            // 否则 "/uploads/../../xxx" 这类路径会指到根目录之外
            if (!targetFilePath.startsWith(baseDirPath)) {
                return false;
            }
            // deleteIfExists：文件不存在不算错误，返回 false 即可（重复删除是常见场景）
            return Files.deleteIfExists(targetFilePath);
        } catch (Exception e) {
            // 删除失败不向上抛异常：删除通常发生在「换头像」这类主流程的收尾阶段，
            // 因删旧文件失败而让整个业务失败，是本末倒置；留下一条日志即可
            return false;
        }
    }

    /**
     * 魔数识别文件真实类型（比对文件头字节，不信任文件名后缀）
     *
     * <p>为什么这样设计：文件名后缀完全由上传者控制，改个名字就能绕过任何基于后缀的检查。
     * 文件开头那几个字节（魔数）由生成它的软件写入，改后缀不会改变它们，
     * 因此它是判断「这到底是什么文件」最便宜也最可靠的手段。
     * 采用白名单而不是黑名单：黑名单永远列不全（.jsp、.php、带脚本的 .svg、
     * 大小写变形……），白名单则天然只放行已知安全的类型。
     * 只识别图片是业务决定的——本系统的上传入口只用于头像与帖子配图。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先判长度：不足 12 字节连比对都不必做，直接判为无法识别；</li>
     *     <li>② 比对 PNG 魔数，命中返回 .png；</li>
     *     <li>③ 比对 JPEG 魔数，命中返回 .jpg；</li>
     *     <li>④ 都不命中返回 null，由调用方判成「格式不支持」。</li>
     * </ol>
     *
     * <p>容易踩的坑：JPEG 只比对前 3 个字节（FF D8 FF），不能强行匹配到第 4 字节——
     * 其后可能是 JFIF(E0)、EXIF(E1) 等不同段，多匹配一个字节就会把
     * 一批合法 JPEG（如相机直出照片）误判成非法。这是「校验过严」造成的误伤，
     * 比漏放更常见，也更难排查。
     * 另外本方法只认图片：本系统的上传入口只用于头像与帖子配图，
     * 将来若要支持 PDF 之类，必须同时补魔数，不能只看后缀放行。
     *
     * @param head 文件头字节，应为文件最前面的若干个字节；为 null 或不足 12 字节时返回 null
     * @return 识别出的扩展名（.png / .jpg，带点号）；无法识别时返回 null，
     * 调用方必须判空，不能直接拼进文件名
     */
    private String detectImageType(byte[] head) {
        // ① 先判长度：不足 12 字节连比对都不必做，直接判为无法识别
        if (head == null || head.length < 12) {
            return null;
        }

        // ② 比对 PNG 魔数，命中返回 .png。
        //    PNG 魔数: 89 50 4E 47 0D 0A 1A 0A（8 字节，后面固定是 IHDR 段）
        byte[] pngMagic = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (matchMagic(head, pngMagic)) {
            return ".png";
        }

        // ③ 比对 JPEG 魔数，命中返回 .jpg。
        //    JPEG/JPG 魔数: FF D8 FF
        //    只比对 3 个字节即可：其后可能是 JFIF(E0)、EXIF(E1) 等不同段，
        //    若强行匹配到第 4 字节，会把一批合法 JPEG（如相机直出照片）误判为非法
        byte[] jpegMagic = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        if (matchMagic(head, jpegMagic)) {
            return ".jpg";
        }

        // ④ 都不命中返回 null，由调用方判成「格式不支持」
        return null;
    }

    /**
     * 比对源字节数组是否以指定魔数开头
     *
     * <p>为什么这样设计：JDK 自带的数组比较没有「前缀比较」语义，手写一个几行的循环
     * 比引入第三方工具类更直接，也更容易看出边界条件。这里只按魔数的长度逐字节比对，
     * 不要求两个数组等长——这正是「前缀」的含义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 按下标逐字节比对，一旦不等立刻返回 false；</li>
     *     <li>② 全部相等返回 true。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法假定 source 的长度不小于 magic（调用方 detectImageType
     * 已保证至少 12 字节，而最长的魔数只有 8 字节）。若将来有新的调用点传入更短的数组，
     * 这里会抛 ArrayIndexOutOfBoundsException——那种异常会被 upload 的 catch 漏掉，
     * 最终变成 B0001「系统错误」，排查时很难联想到是数组越界。
     *
     * @param source 待检查的字节数组（文件头），长度必须不小于 magic
     * @param magic 魔数，长度不超过 source
     * @return source 以 magic 开头返回 true，否则返回 false
     */
    private boolean matchMagic(byte[] source, byte[] magic) {
        // ① 按下标逐字节比对，一旦不等立刻返回 false
        for (int i = 0; i < magic.length; i++) {
            if (source[i] != magic[i]) {
                return false;
            }
        }
        // ② 全部相等返回 true
        return true;
    }
}

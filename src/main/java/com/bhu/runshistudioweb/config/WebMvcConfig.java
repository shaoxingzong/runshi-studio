package com.bhu.runshistudioweb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring MVC 静态资源映射：把上传目录暴露成可访问的 URL
 *
 * author: shaoshing
 *
 * <p>为什么需要它：{@code FileManager} 只是把文件写进磁盘目录，磁盘目录默认**不是** Web 可访问路径。
 * 本类把 URL {@code /uploads/**} 映射到磁盘上的上传根目录，前端才能用
 * {@code <img src="/api/uploads/2026/09/xxx.png">} 直接展示图片。
 *
 * <p>完整链路：{@code POST /api/file/upload} 落盘 → 返回 {@code /uploads/2026/09/xxx.png}
 * → 前端拼上 context-path 直接请求 → 由本类映射的静态资源处理器返回文件字节。
 *
 * <p>两个必须注意的点：
 * <ol>
 *     <li><b>注意 {@code @Value} 的 import</b>：必须是
 *     {@code org.springframework.beans.factory.annotation.Value}。
 *     若误写成 Lettuce 的 {@code io.lettuce.core.dynamic.annotation.Value}（同名不同包，
 *     IDE 自动补全很容易选错），Spring 不会注入，{@code rootDir} 保持 null，
 *     {@code Paths.get(null)} 会抛空指针；更隐蔽的是：这类注解误用会让 javac 直接跳过注解处理，
 *     导致 Lombok 生成的 getter/setter 全部"找不到符号"，报出几百条迷惑性错误；</li>
 *     <li><b>静态资源也必须过鉴权</b>：{@code /uploads/**} 需要在 Sa-Token 白名单里
 *     （见 {@link SaTokenMvcConfig}），否则图片 URL 对未登录的游客会返回 40100，官网页面上就是一片裂图。</li>
 * </ol>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 上传文件的存储根目录，与 {@code FileManager} 读的是同一个配置项
     */
    @Value("${studio.file.root-dir}")
    private String rootDir;

    /**
     * 注册静态资源映射
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 强制转为规范的绝对路径，解决 spring-boot:run 与 jar 部署时相对路径工作目录不一致的问题
        // （相对路径 ./data/uploads 在 IDEA 里是项目根目录，打成 jar 后却是 jar 所在目录）
        Path absolutePath = Paths.get(rootDir).toAbsolutePath().normalize();

        // 使用 toUri().toString() 自动生成跨平台标准的 file:///... 格式 URI
        // （Windows 的反斜杠与盘符在资源定位符里必须转成这种形式，否则映射会静默失效、图片 404）
        String resourceLocation = absolutePath.toUri().toString();
        if (!resourceLocation.endsWith("/")) {
            resourceLocation += "/";
        }

        // 映射 URL /uploads/** 到本地磁盘目录，配置 1 小时 (3600 秒) 浏览器长缓存。
        // 缓存时间可以放心设长：文件名是 UUID，内容永远不会被覆盖（改图会生成新 URL）
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourceLocation)
                .setCachePeriod(3600);
    }
}

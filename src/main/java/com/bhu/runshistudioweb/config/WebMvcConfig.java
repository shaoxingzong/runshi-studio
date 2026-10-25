package com.bhu.runshistudioweb.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring MVC 静态资源映射：把磁盘上的上传目录暴露成可以用 URL 访问的路径
 *
 * <p>为什么需要它：FileManager 只负责把文件写进磁盘目录，而磁盘目录默认不是 Web 可访问路径。
 * 浏览器发请求时，Tomcat 只会去 classpath 里的 static/public 目录找文件，
 * 根本不知道磁盘上那个上传目录的存在。本类就是告诉 Spring MVC：
 * 「URL 上 /uploads/** 开头的请求，请到磁盘上的上传根目录里去找对应的文件」。
 *
 * <p>完整链路：POST /api/file/upload 落盘 → 返回 /uploads/2026/09/xxx.png
 * → 前端拼上 context-path 直接请求 → 由本类映射的静态资源处理器返回文件字节。
 *
 * <p>两个必须注意的点：
 * <ul>
 *     <li>@Value 的 import 必须是 org.springframework.beans.factory.annotation.Value。
 *     若误写成 Lettuce 的 io.lettuce.core.dynamic.annotation.Value（同名不同包，
 *     IDE 自动补全很容易选错），Spring 不会注入，rootDir 保持 null，
 *     Paths.get(null) 会抛空指针。这类注解误用的排查成本很高，因为报错位置离原因很远；</li>
 *     <li>静态资源也必须过鉴权：/uploads/** 需要在 Sa-Token 白名单里，
 *     否则图片 URL 对未登录的游客会返回未登录错误，官网页面上就是一片裂图。</li>
 * </ul>
 *
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 上传文件的存储根目录，与 FileManager 读的是同一个配置项
     *
     * <p>为什么写成 final 字段还能拿到配置值：本项目在 lombok.config 里配置了
     * lombok.copyableAnnotations，把 @Value 复制到 Lombok 生成的构造器参数上，
     * 于是 Spring 在创建本 Bean 时能按参数注入配置值。
     * 这样既保留了「依赖不可变」（final），又不用为它手写一个构造器。
     * 如果哪天这条 lombok.config 被删掉，本字段会注入失败并报「无法解析占位符」。
     */
    @Value("${studio.file.root-dir}")
    private final String rootDir;

    /**
     * 注册静态资源映射：把 URL /uploads/** 指向磁盘上的上传根目录
     *
     * <p>为什么这样设计：映射的两端必须来自同一个配置项（studio.file.root-dir），
     * 一旦这里和 FileManager 读的配置不一致，症状是「上传成功、但图片 404」——
     * 文件确实写进去了，只是写在了一个没人访问的目录里。这种问题从接口返回看不出任何异常。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>把配置里的目录转成绝对路径并规范化。必须做这一步，因为相对路径
     *     （如 ./data/uploads）的基准是「进程的工作目录」：在 IDEA 里跑是项目根目录，
     *     打成 jar 后却是 jar 所在目录，同一个配置在两处指向不同的文件夹。
     *     normalize 顺带消掉路径里的 . 与 ..，让后面的 URI 转换结果稳定；</li>
     *     <li>把绝对路径转成 file:/// 形式的 URI 字符串。这是本方法最关键的一步：
     *     Spring 的资源定位器要求的是 URI，不是「文件路径」。Windows 下的
     *     「盘符 + 反斜杠」写法（形如 D 冒号 反斜杠 data 反斜杠 uploads）
     *     直接拼成 file:D 冒号 反斜杠 data 是不合法的 URI
     *     （反斜杠与盘符冒号都必须转义成 /D:/ 的形式），映射会静默失效、图片 404。
     *     用 toUri() 让 JDK 负责转义，就不必自己处理 Windows 与 Linux 的差异；</li>
     *     <li>补一个结尾的斜杠。资源定位符必须指向「目录」，缺了斜杠时
     *     Spring 会把它当成一个文件名，拼接子路径时得到 /uploads/2026/09 变成
     *     .../uploads2026/09 这种错误结果；</li>
     *     <li>注册映射并设置浏览器缓存 1 小时。缓存时间可以放心设长，
     *     因为文件名是 UUID，内容永远不会被覆盖——换图会产生一个新 URL，
     *     旧 URL 的缓存不会让用户看到过期的图片。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只负责「读得到」，不负责「谁都能读」。
     * 如果 Sa-Token 的拦截器覆盖了 /**，静态资源同样会被拦截，
     * 必须在白名单里放行 /uploads/**，否则游客访问图片会拿到未登录错误码。
     *
     * @param registry 资源处理器注册表，由 Spring MVC 在启动时传入；
     *                 本方法只是往里登记规则，不会立刻读取任何文件。
     *                 目录即使不存在也不会报错，只是访问时 404——所以别指望它帮你校验配置
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

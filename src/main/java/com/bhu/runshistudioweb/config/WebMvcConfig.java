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
 * FileManager 只负责把文件写进磁盘，而磁盘目录默认不是 Web 可访问路径：Tomcat 只会去 classpath
 * 里的 static/public 目录找文件。本类告诉 Spring MVC：/uploads/** 的请求到上传根目录里去找。
 *
 * 坑一：@Value 必须是 org.springframework.beans.factory.annotation.Value。误写成 Lettuce 的
 * 同名注解时 Spring 不会注入，Paths.get(null) 抛空指针，而报错位置离原因很远。
 * 坑二：/uploads/** 必须在 Sa-Token 白名单里，否则官网页面上就是一片裂图。
 *
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 上传文件的存储根目录，与 FileManager 读的是同一个配置项
     *
     * final 字段能拿到配置值，靠的是 lombok.config 里的 copyableAnnotations 把 @Value
     * 复制到生成的构造器参数上。那条配置被删掉时，本字段注入失败并报「无法解析占位符」。
     */
    @Value("${studio.file.root-dir}")
    private final String rootDir;

    /**
     * 注册静态资源映射：把 URL /uploads/** 指向磁盘上的上传根目录
     *
     * 映射的两端必须来自同一个配置项（studio.file.root-dir），不一致的症状是
     * 「上传成功、但图片 404」——文件确实写进去了，只是写在没人访问的目录里。
     *
     * 坑一：必须转成绝对路径。相对路径的基准是进程的工作目录，在 IDEA 里跑是项目根目录，
     * 打成 jar 后却是 jar 所在目录。
     * 坑二：必须用 toUri() 转成 file:/// 形式。Windows 的「盘符 + 反斜杠」直接拼出的不是
     * 合法 URI，映射会静默失效、图片 404。
     * 坑三：结尾斜杠不能缺。资源定位符必须指向目录，缺了斜杠 Spring 会把它当成文件名。
     *
     * @param registry 资源处理器注册表；目录即使不存在也不会报错，只是访问时 404
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

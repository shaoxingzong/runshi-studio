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
 * @author shaoshing
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 上传文件的存储根目录，与 FileManager 读的是同一个配置项 */
    @Value("${studio.file.root-dir}")
    private final String rootDir;

    /** 注册静态资源映射：把 URL /uploads/** 指向磁盘上的上传根目录 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path absolutePath = Paths.get(rootDir).toAbsolutePath().normalize();

        String resourceLocation = absolutePath.toUri().toString();
        if (!resourceLocation.endsWith("/")) {
            resourceLocation += "/";
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourceLocation)
                .setCachePeriod(3600);
    }
}

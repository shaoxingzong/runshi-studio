package com.bhu.runshistudioweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类：整个应用的唯一入口
 *
 * @EnableScheduling 不能省：AI 配额走「Redis 累加 + 定时回刷」，缺了它 @Scheduled 既不报错也不执行，表现为「Redis 计数一直涨、数据库始终是旧值」。
 * @author shaoshing
 */
@SpringBootApplication
@EnableScheduling
public class RunshiStudioWebApplication {

    /**
     * 应用入口：把启动类交给 Spring 后，由框架完成容器创建与内嵌 Web 服务器启动
     *
     * @param args 命令行参数，如 --server.port=9090 可覆盖 yml 中的配置；不传则完全按配置文件启动，传了未知参数也不会报错，只是被忽略
     */
    public static void main(String[] args) {
        // 返回值是 ConfigurableApplicationContext，需要动态获取 Bean 时才用；常规启动无需接收
        SpringApplication.run(RunshiStudioWebApplication.class, args);
    }

}

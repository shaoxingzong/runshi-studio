package com.bhu.runshistudioweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类：整个应用的唯一入口
 *
 * 必须待在最外层包 com.bhu.runshistudioweb：@ComponentScan 从本类所在包向下扫，
 * 挪进子包会让同级的 controller、service、config 全部扫不到，且不报错（接口 404、拦截器静默失效）。
 *
 * @EnableScheduling 不能省：AI 配额走「Redis 累加 + 定时回刷」，缺了它 @Scheduled
 * 既不报错也不执行，表现为「Redis 计数一直涨、数据库始终是旧值」。
 *
 * @author shaoshing
 */
@SpringBootApplication
@EnableScheduling
public class RunshiStudioWebApplication {

    /**
     * 应用入口：把启动类交给 Spring 后，由框架完成容器创建与内嵌 Web 服务器启动
     *
     * 入口方法只做一件事——把「配置从哪里来」交给框架。扫描、自动装配、启动 Tomcat
     * 全在 SpringApplication.run 内部完成，在这里写任何业务初始化（比如手动 new 一个 Service）
     * 都会绕过容器管理，拿到的对象没有依赖注入、没有事务代理。
     * 需要在启动时做事，应当实现 CommandLineRunner 或用 @PostConstruct。
     *
     * args 必须原样透传：--server.port=9090 这类参数优先级高于 yml，
     * 让运维能在不改包的前提下覆盖配置。
     *
     * 坑：本方法必须声明为 public static，写成非 static 的话 JVM 根本找不到入口。
     *
     * @param args 命令行参数，如 --server.port=9090 可覆盖 yml 中的配置；
     *             不传则完全按配置文件启动，传了未知参数也不会报错，只是被忽略
     */
    public static void main(String[] args) {
        // 返回值是 ConfigurableApplicationContext，需要动态获取 Bean 时才用；常规启动无需接收
        SpringApplication.run(RunshiStudioWebApplication.class, args);
    }

}

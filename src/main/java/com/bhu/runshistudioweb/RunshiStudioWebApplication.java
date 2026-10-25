package com.bhu.runshistudioweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类：整个应用的唯一入口
 *
 * <p>为什么它必须待在最外层包 com.bhu.runshistudioweb：@SpringBootApplication 里含
 * @ComponentScan，扫描起点就是「本类所在的包」，并且只向下扫子包。
 * 把本类挪进 config 或 controller 这类子包后，与它同级的 controller、service、config
 * 全部落在扫描范围之外，表现为接口 404、@Configuration 不生效、拦截器不注册。
 * 这种故障最麻烦的地方是「不报错」——在 Spring 看来「没扫到」和「没写过」完全一样，
 * 启动日志里找不到任何异常线索。
 *
 * <p>@SpringBootApplication 是三件事的组合：
 * <ul>
 *     <li>@SpringBootConfiguration：声明本类是一个配置类，可以写 @Bean 方法；</li>
 *     <li>@EnableAutoConfiguration：按 classpath 上的依赖自动装配 Bean
 *     （数据源、RedisTemplate、JsonMapper、MVC 等），这也是本项目几乎不写 XML 的原因；</li>
 *     <li>@ComponentScan：从本类所在包开始向下扫描 @Component/@Service/@Mapper 等。</li>
 * </ul>
 *
 * <p>为什么加 @EnableScheduling：AI 提问配额走「Redis 累加 + 定时回刷」，
 * 需要周期性把 Redis 里的增量写回数据库（实现见 AiQueryCountManager#flushToDb）。
 * @Scheduled 只是一个标记，真正让它被调度的是本注解注册进来的后处理器；
 * 少了这一行，@Scheduled 方法既不报错也不执行，是彻底的静默失效——
 * 表现为「Redis 里的计数一直涨，数据库里始终是旧值」，往往要等重启丢了计数才被发现。
 *
 * <p>启动后访问地址：http://localhost:8080/api（端口与 context-path 见 application.yml）
 *
 * @author shaoshing
 */
@SpringBootApplication
@EnableScheduling
public class RunshiStudioWebApplication {

    /**
     * 应用入口：把启动类交给 Spring 后，由框架完成容器创建与内嵌 Web 服务器启动
     *
     * <p>为什么这样设计：入口方法只做一件事——把「配置从哪里来」交给框架。
     * 扫描、自动装配、启动 Tomcat 全在 SpringApplication.run 内部完成，
     * 在这里写任何业务初始化（比如手动 new 一个 Service）都会绕过容器管理，
     * 拿到的对象没有依赖注入、没有事务代理，等于自己制造一个半残的 Bean。
     * 需要在启动时做事，应该实现 CommandLineRunner 或用 @PostConstruct（如 StartupSelfCheck）。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>把启动类的 Class 对象传给 SpringApplication：框架据此确定包扫描的起点；</li>
     *     <li>把 main 方法的 args 原样透传：--server.port=9090 这类参数优先级高于 yml，
     *     放在这里传是为了让运维能在不改包的前提下覆盖配置；</li>
     *     <li>run 返回后容器已就绪，Web 端口已开始监听，此后才对外提供服务。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法必须声明为 public static，且不能有返回值——
     * 写成非 static 的话 JVM 根本找不到入口，IDE 里会提示「没有 main 方法」；
     * 另外不要把 SpringApplication.run 的返回值丢掉这件事当成缺陷，
     * 只有需要在 main 里继续操作容器（例如读取某个 Bean 打印信息）时才需要接住它。
     *
     * @param args 命令行参数，如 --server.port=9090 可覆盖 yml 中的配置；
     *             不传则完全按配置文件启动，传了未知参数也不会报错，只是被忽略
     */
    public static void main(String[] args) {
        // 返回值是 ConfigurableApplicationContext，需要动态获取 Bean 时才用；常规启动无需接收
        SpringApplication.run(RunshiStudioWebApplication.class, args);
    }

}

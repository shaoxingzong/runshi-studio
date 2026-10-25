package com.bhu.runshistudioweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类
 *
 *
 * <p>@SpringBootApplication 是三个注解的组合：
 * <ul>
 *     <li>@SpringBootConfiguration：声明本类是一个配置类；</li>
 *     <li>@EnableAutoConfiguration：按 classpath 上的依赖自动装配 Bean
 *     （数据源、RedisTemplate、JsonMapper、MVC 等），这也是本项目几乎不写 XML 的原因；</li>
 *     <li>@ComponentScan：从本类所在包开始向下扫描 @Component/@Service/@Mapper 等。</li>
 * </ul>
 *
 * <p>关键约束：扫描范围是「当前类所在包及其子包」，所以启动类必须待在最外层包
 * （com.bhu.runshistudioweb）。一旦把它挪进子包，controller、config 等就不会被扫描到，
 * 表现为接口 404、拦截器静默失效这类难查的问题。
 *
 * <p>为什么加 @EnableScheduling：AI 提问配额计数走「Redis 累加 + 定时回刷」，
 * 需要周期性把 Redis 里的增量落回 sys_user.ai_query_count
 * （见 AiQueryCountManager#flushToDb）。
 * Spring 的 @Scheduled 默认不生效——必须由本注解注册
 * ScheduledAnnotationBeanPostProcessor 才会扫描并调度它。
 * 少了这一行不会报错，任务只是静默不执行：表现为「Redis 里的计数越堆越多、
 * 但 DB 永远是 0」，直到重启丢数据才被发现。
 *
 * <p>启动后访问地址：http://localhost:8080/api（端口与 context-path 见 application.yml）
 *
 * @author shaoshing
 */
 */
@SpringBootApplication
@EnableScheduling
public class RunshiStudioWebApplication {

    /**
     * 应用入口
     *
     * @param args 命令行参数，如 --server.port=9090 可覆盖配置
     */
    public static void main(String[] args) {
        // 返回值是 ConfigurableApplicationContext，需要动态获取 Bean 时才用；常规启动无需接收
        SpringApplication.run(RunshiStudioWebApplication.class, args);
    }

}

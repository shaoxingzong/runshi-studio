package com.bhu.runshistudioweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类
 *
 * author: shaoshing
 *
 * <p>{@code @SpringBootApplication} 是三个注解的组合：
 * <ul>
 *     <li>{@code @SpringBootConfiguration}：声明本类是一个配置类；</li>
 *     <li>{@code @EnableAutoConfiguration}：按 classpath 上的依赖自动装配 Bean
 *     （数据源、RedisTemplate、JsonMapper、MVC 等），这也是本项目几乎不写 XML 的原因；</li>
 *     <li>{@code @ComponentScan}：从本类所在包开始**向下**扫描 {@code @Component/@Service/@Mapper} 等。</li>
 * </ul>
 *
 * <p><b>关键约束</b>：扫描范围是「当前类所在包及其子包」，所以启动类必须待在最外层包
 * （{@code com.bhu.runshistudioweb}）。一旦把它挪进子包，controller、config 等就不会被扫描到，
 * 表现为接口 404、拦截器静默失效这类难查的问题。
 *
 * <p>启动后访问地址：{@code http://localhost:8080/api}（端口与 context-path 见 application.yml）
 *
 * <p><b>为什么加 {@code @EnableScheduling}</b>：AI 提问配额计数改为
 * {@code Redis INCR + 定时回刷} 之后，需要有一个任务周期性地把 Redis 里的增量落回
 * {@code sys_user.ai_query_count}（见 {@code AiQueryCountManager#flushToDb}）。
 * Spring 的 {@code @Scheduled} <b>默认不生效</b>——必须由本注解注册
 * {@code ScheduledAnnotationBeanPostProcessor} 才会扫描并调度它。
 * 少了这一行不会报错，任务只是<b>静默不执行</b>：表现为「Redis 里的计数越堆越多、
 * 但 DB 永远是 0」，直到重启丢数据才被发现。
 */
@SpringBootApplication
@EnableScheduling
public class RunshiStudioWebApplication {

    /**
     * 应用入口
     *
     * @param args 命令行参数，如 {@code --server.port=9090} 可覆盖配置
     */
    public static void main(String[] args) {
        // 返回值是 ConfigurableApplicationContext，需要动态获取 Bean 时才用；常规启动无需接收
        SpringApplication.run(RunshiStudioWebApplication.class, args);
    }

}

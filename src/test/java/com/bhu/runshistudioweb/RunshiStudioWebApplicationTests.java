package com.bhu.runshistudioweb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动冒烟测试
 *
 * author: shaoshing
 *
 * <p>只做一件事：把整个 Spring 容器装配起来。方法体是空的，但它的价值在于——
 * 只要有任何 Bean 装配不上（漏了依赖、配置项写错、循环依赖、Mapper 扫描不到），
 * 这个测试就会失败，而不是等到手动启动应用时才发现。
 *
 * <p>注意：本测试会真实创建数据源、Redis 连接工厂等 Bean，
 * 因此运行前需要 MySQL 与 Redis 处于可用状态（否则会被 @SpringBootTest 的启动失败卡住）。
 */
@SpringBootTest
class RunshiStudioWebApplicationTests {

    @Test
    void contextLoads() {
    }

}

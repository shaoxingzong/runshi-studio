package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus 配置类验收测试
 *
 * author: shaoshing
 *
 * <p>本测试不执行任何 SQL，只验证拦截器是否真的注册进了 Spring 容器。
 * 这类「静默失效」是 MP 最典型的坑：漏写 {@code @Bean} 时程序照常启动、查询照常返回，
 * 只是 LIMIT 不生效（返回全表），等数据量上来才发现，成本很高。
 */
@SpringBootTest
class MyBatisPlusConfigTest {

    // 用 ApplicationContext 而不是直接 @Autowired 拦截器：
    // 这样「Bean 是否存在」本身就是断言的一部分，拿不到实例即说明配置失效
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("MybatisPlusInterceptor 必须注册为 Bean（否则分页插件静默失效）")
    void interceptorIsRegisteredAsBean() {
        MybatisPlusInterceptor interceptor =
                applicationContext.getBean(MybatisPlusInterceptor.class);
        assertFalse(interceptor.getInterceptors().isEmpty(),
                "拦截器链为空：MyBatisPlusConfig 中 mybatisPlusInterceptor() 方法很可能漏写了 @Bean 注解");
    }

    @Test
    @DisplayName("拦截器链中必须包含分页拦截器")
    void paginationInterceptorPresent() {
        MybatisPlusInterceptor interceptor =
                applicationContext.getBean(MybatisPlusInterceptor.class);
        // 用 instanceof 精确断言类型：链上可能有其他插件，不能只断言「非空」，
        // 否则加错插件（比如误加了别的 InnerInterceptor）也能通过
        boolean hasPagination = interceptor.getInterceptors().stream()
                .anyMatch(i -> i instanceof PaginationInnerInterceptor);
        assertTrue(hasPagination, "拦截器链中未找到 PaginationInnerInterceptor");
    }
}

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
 * @author shaoshing
 */
@SpringBootTest
class MyBatisPlusConfigTest {

    /** 用 ApplicationContext 而不是直接 @Autowired 拦截器：这样「Bean 是否存在」本身就是断言的一部分，拿不到实例即说明配置失效 */
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
        boolean hasPagination = interceptor.getInterceptors().stream()
                .anyMatch(i -> i instanceof PaginationInnerInterceptor);
        assertTrue(hasPagination, "拦截器链中未找到 PaginationInnerInterceptor");
    }
}

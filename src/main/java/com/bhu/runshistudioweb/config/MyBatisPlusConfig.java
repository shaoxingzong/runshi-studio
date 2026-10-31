package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：只负责两件事——扫描 Mapper 接口、注册插件拦截器链
 *
 * @author shaoshing
 */
@Configuration
@MapperScan("com.bhu.runshistudioweb.mapper")
public class MyBatisPlusConfig {

    /**
     * 组装 MyBatis-Plus 的拦截器链，目前只挂分页插件
     *
     * @return 已装配分页插件的拦截器链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

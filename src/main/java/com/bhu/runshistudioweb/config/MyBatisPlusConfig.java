package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * author: shaoshing
 *
 * <p>职责只有两件事：扫描 Mapper 接口 + 注册插件拦截器链。
 * 逻辑删除值、自动填充字段等「全局策略」在 application-dev.yml 与
 * {@link MyMetaObjectHandler} 中，不在本类。
 *
 * <p>注意：本类必须被 Spring 扫描到（放在启动类的子包下），
 * 否则拦截器不会被注册，分页会**静默失效**——查询不报错，只是 LIMIT 不生效，
 * 返回全表数据，是很难发现的问题。{@code MyBatisPlusConfigTest} 就是为此写的验收测试。
 */
@Configuration
@MapperScan("com.bhu.runshistudioweb.mapper")
public class MyBatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器链（分页功能依赖它）
     *
     * <p>注意：方法必须加 {@code @Bean}，否则 new 出来的拦截器不会进入 Spring 容器，
     * 分页照样静默失效。
     *
     * @return 已装配分页拦截器的插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器：自动在 SQL 末尾拼接 LIMIT，并额外执行一条 COUNT 查询用于返回总数。
        // 它必须放在拦截器链的最后：其他插件（如多租户、动态表名）会改写 SQL，
        // 若分页先执行，改写过后的 SQL 与 LIMIT 拼接位置就会出错
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

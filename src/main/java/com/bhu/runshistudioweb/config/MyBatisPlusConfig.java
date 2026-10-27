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
 * 逻辑删除、主键策略、自动填充不堆在这里，它们由三个不同机制承担：
 * 逻辑删除在 mybatis-plus.global-config.db-config，填充值在 MyMetaObjectHandler，
 * 哪些字段要填由实体上的 @TableField(fill = ...) 决定。
 *
 * 坑：本类必须被 Spring 扫描到。@Configuration 类不被扫描，里面的 @Bean 就不会执行，
 * 拦截器不入容器，分页会静默失效——查询不报错，只是拼不上 LIMIT，一次请求把全表捞回来。
 *
 * @author shaoshing
 */
@Configuration
@MapperScan("com.bhu.runshistudioweb.mapper")
public class MyBatisPlusConfig {

    /**
     * 组装 MyBatis-Plus 的拦截器链，目前只挂分页插件
     *
     * 分页插件必须在链尾：它要在所有 SQL 改写完成之后才能确定最终语句，再据此拼 LIMIT
     * 与生成 COUNT。排在前面的话，后面插件改写 SQL 时 LIMIT 已被固定在前半段。
     *
     * 坑一：必须显式指定 DbType.MYSQL。不指定时插件要靠连接去猜，猜错时生成的方言与真实
     * 数据库不匹配，报的是 SQL 语法错误，排查方向容易被带到 SQL 本身。
     * 坑二：别忘了方法上的 @Bean，漏掉它拦截器就是一个用完即弃的局部变量。
     *
     * @return 已装配分页插件的拦截器链
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

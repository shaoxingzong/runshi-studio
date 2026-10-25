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
 * <p>为什么不把逻辑删除、主键策略、自动填充都堆在这里：
 * 它们分别由三个不同的机制承担，放在本类只会让人误以为改这里就能改全局行为。
 * <ul>
 *     <li>逻辑删除与删除值：mybatis-plus.global-config.db-config（见 application.yml）；</li>
 *     <li>审计字段填什么值：MyMetaObjectHandler；</li>
 *     <li>哪些字段需要填：实体字段上的 @TableField(fill = ...)，本类管不着。</li>
 * </ul>
 *
 * <p>为什么 @MapperScan 是必需的：MyBatis 的 Mapper 是「接口」，运行时靠动态代理生成实现。
 * 只写 @Mapper 注解的接口可以被扫描到，但项目统一用 @MapperScan 指定包，
 * 好处是扫描范围明确、启动更快，也不会因为某个接口漏写 @Mapper 而突然注入失败。
 * 一旦包名写错，症状是启动直接失败（NoSuchBeanDefinitionException），比运行期才炸好查得多。
 *
 * <p>为什么本类必须被 Spring 扫描到（即位于启动类的子包下）：@Configuration 类不被扫描，
 * 里面的 @Bean 就不会执行，拦截器不入容器，分页会静默失效——
 * 查询不报错，只是拼不上 LIMIT，一次请求把全表捞回来。
 * 数据量小的时候完全看不出来，等到表大了才发现接口变慢，而那时早已记不清是哪次改动引入的。
 *
 * @author shaoshing
 */
@Configuration
@MapperScan("com.bhu.runshistudioweb.mapper")
public class MyBatisPlusConfig {

    /**
     * 组装 MyBatis-Plus 的拦截器链，目前只挂分页插件
     *
     * <p>为什么这样设计：MyBatis-Plus 把「改写 SQL」的能力做成了可插拔的拦截器链
     * （分页、多租户、动态表名、防全表更新删除都在这一层）。做成链而不是写死在框架里，
     * 是为了让多个插件按顺序叠加。代价是顺序有讲究——顺序错了不会报错，只会产出错误的 SQL。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>创建空的拦截器链容器：此时还没有任何插件，直接返回它等于没配；</li>
     *     <li>把分页插件挂到链尾：分页插件必须在最后，因为它要在「所有改写都完成之后」
     *     才能确定最终 SQL 长什么样，再据此拼接 LIMIT 与生成 COUNT 语句。
     *     若排在前面，后面插件改写 SQL 时 LIMIT 已被固定在前半段，语句结构就乱了；</li>
     *     <li>把链交还给 Spring：加了 @Bean 才会被容器接管，new 出来的对象本身没有意义。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里显式指定了 DbType.MYSQL。不指定时插件要靠 JDBC 连接去猜数据库类型，
     * 首次分页会多一次元数据查询；更要紧的是猜错时生成的方言（例如 SQL Server 的
     * OFFSET/FETCH）与真实数据库不匹配，报的是 SQL 语法错误，排查方向容易被带到 SQL 本身。
     * 另外别忘了方法上的 @Bean：漏掉它，拦截器就是一个普通的局部变量，用完即弃。
     *
     * @return 已装配分页插件的拦截器链，交给 MyBatis-Plus 在每次执行 SQL 时调用
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

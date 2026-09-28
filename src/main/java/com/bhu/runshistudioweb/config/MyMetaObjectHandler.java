package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充
 *
 * author: shaoshing
 *
 * <p>为什么用应用层填充而不是数据库的 DEFAULT CURRENT_TIMESTAMP：
 * 数据库默认值在 INSERT 之后无法回填到实体对象，接口就没法立刻返回带创建时间的完整数据；
 * 而且时区行为会分散在 MySQL 与 JVM 两处。统一走这里，一处可控（见 db/DESIGN.md ADR-2）。
 *
 * <p><b>重要</b>：{@code created_at} / {@code created_by} 等列在 DDL 中是 NOT NULL 且没有 DB 默认值，
 * 一旦本类失效（例如忘了加 {@code @Component}），**所有插入都会失败**，而不是悄悄写入 null。
 *
 * <p>填充规则由实体字段上的注解决定：{@code @TableField(fill = FieldFill.INSERT)} 才会在插入时填充，
 * 所以本类只负责「填什么值」，不负责「哪些字段要填」。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充：创建/更新人、创建/更新时间
     *
     * <p>注意：这里用的必须是 **Java 实体类的属性名（createdAt）**，
     * 绝不能写数据库列名（created_at），否则填充会静默不生效。
     *
     * @param metaObject 当前操作的实体元信息，由 MyBatis-Plus 传入
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // strictInsertFill 只在字段当前为 null 时才填充：
        // 业务方显式赋值（例如数据迁移要保留原始创建时间）时不会被覆盖
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());

        // 创建人/更新人：当前还没有登录上下文，先用 0L 占位。
        // 接入鉴权后应改为从 SecurityContext / UserContext 中取当前登录用户 ID，
        // 否则审计字段失去意义（见 SysUser 的 createdBy / updatedBy）
        this.strictInsertFill(metaObject, "createdBy", Long.class, 0L);
        this.strictInsertFill(metaObject, "updatedBy", Long.class, 0L);
    }

    /**
     * 更新时填充：只动更新人、更新时间，创建人/创建时间绝不能改
     *
     * <p>注意：{@code updateFill} 只在通过 MyBatis-Plus 的 update 方法（实体或 wrapper 更新）触发，
     * 手写 XML 的自定义 SQL 不会走这里，需要自己维护这两个字段。
     *
     * @param metaObject 当前操作的实体元信息，由 MyBatis-Plus 传入
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, 0L);
    }
}

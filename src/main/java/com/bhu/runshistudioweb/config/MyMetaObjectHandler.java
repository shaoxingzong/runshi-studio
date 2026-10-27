package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：把 created_at / updated_at / created_by / updated_by 的赋值集中到一处
 *
 * 不用数据库的 DEFAULT CURRENT_TIMESTAMP：默认值只在 INSERT 时生效，值进不了实体对象；
 * 而且 created_by / updated_by 是数据库完全不知道的信息，只有应用层能填。
 *
 * 前提：这些列在 DDL 中是 NOT NULL 且没有数据库默认值。这是刻意的——本类一旦失效
 * （例如漏了 @Component），插入会直接报字段不能为空，而不是悄悄写入 null。
 *
 * @author shaoshing
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充创建人 / 创建时间与更新人 / 更新时间
     *
     * 插入时 updated_at 也必须有值，否则「最近修改时间」在新建数据上就是 null，
     * 按时间排序的列表会把新数据排到最后。
     *
     * 坑一：第一个参数必须是属性名 createdAt 而不是列名 created_at。名字对不上时
     * MyBatis-Plus 不报错，只是安静地什么都不做。
     * 坑二：strictInsertFill 只在字段当前为 null 时填充，因此业务方显式赋值不会被覆盖；
     * 反过来说，它保证的是「不覆盖」而不是「保证正确」。
     *
     * @param metaObject 当前操作的实体元信息，内部包装的是真正的实体对象
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
     * 更新时填充更新人与更新时间，创建人 / 创建时间绝不触碰
     *
     * 审计的核心价值是「创建信息不可篡改」：更新时若也顺手填一遍 createdBy，
     * 创建人就变成了最后修改的那个人，数据看起来没问题，追溯链却已经断了。
     *
     * 坑：本方法只在走 MyBatis-Plus 的 update 方法时触发。手写 XML 的自定义 SQL
     * 或 JdbcTemplate 执行的 UPDATE 都不会经过这里，症状是「某些更新后修改时间不动」。
     *
     * @param metaObject 当前操作的实体元信息；用 Wrapper 更新且没传实体时，
     *                   对应属性可能不存在，填充会被静默跳过
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, 0L);
    }
}

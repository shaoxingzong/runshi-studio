package com.bhu.runshistudioweb.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：把 created_at / updated_at / created_by / updated_by 的赋值集中到一处
 *
 * <p>为什么用应用层填充，而不是数据库的 DEFAULT CURRENT_TIMESTAMP：
 * <ul>
 *     <li>数据库默认值只在 INSERT 时生效，值进不了实体对象——插入之后想立刻把
 *     带创建时间的完整数据返回给前端，还得再查一次库，凭空多一次往返；</li>
 *     <li>时区行为会分散在 MySQL 与 JVM 两处：MySQL 的 NOW() 取服务器时区，
 *     Java 的 LocalDateTime.now() 取 JVM 时区，两者不一致时同一行数据的两个时间字段会差 8 小时，
 *     而这类错误只在跨时区部署时才暴露；</li>
 *     <li>created_by / updated_by 是数据库完全不知道的信息（当前操作人是谁），
 *     只有应用层能填，既然如此，四个字段放在一起维护最不容易漏。</li>
 * </ul>
 *
 * <p>重要前提：created_at 等列在 DDL 中是 NOT NULL 且没有数据库默认值。
 * 这是刻意的——一旦本类失效（例如漏了 @Component），插入会直接失败并报字段不能为空，
 * 而不是悄悄写入 null。让错误在第一时间炸出来，比让它变成一堆 null 数据强得多。
 *
 * <p>填充规则由实体字段上的注解决定：只有标了 @TableField(fill = FieldFill.INSERT) 的字段
 * 才会在插入时进入本类，所以本类只负责「填什么值」，不负责「哪些字段要填」。
 * 这也是为什么这里可以无脑对四个字段都调一次填充方法，没标记的字段会被直接跳过。
 *
 * @author shaoshing
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充创建人/创建时间与更新人/更新时间
     *
     * <p>为什么这样设计：四条语句看起来重复（创建与更新在插入时取同一个时刻），
     * 但语义必须分开。插入时 updated_at 也必须有值，否则「最近修改时间」在新建数据上就是 null，
     * 按时间排序的列表会把新数据排到最后。用两个独立字段而不是复用一个，
     * 是为了将来能区分「从未被改过」与「改过但内容没变」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>填 createdAt：必须是属性名 createdAt 而不是列名 created_at。
     *     MyBatis-Plus 通过反射在实体对象上找这个属性，名字对不上时它不会报错，
     *     只是安静地什么都不做——这是本类最容易踩的坑；</li>
     *     <li>填 updatedAt：与上一步同一时刻，保证新建数据的两个时间相等；</li>
     *     <li>填 createdBy / updatedBy：当前没有登录上下文，先用 0L 占位，
     *     含义是「系统写入」。审计字段宁可先有一个明确的占位值，也不要留空——
     *     留空会让「谁创建的」这个问题永远无法回答，而占位值至少能被识别出来。</li>
     * </ol>
     *
     * <p>容易踩的坑：strictInsertFill 只在字段当前为 null 时才填充，
     * 所以业务方显式赋值（例如数据迁移要保留原始创建时间）不会被覆盖。
     * 反过来说，如果某个业务场景手动把 createdAt 设成了错误的值，这里也不会纠正它——
     * 它保证的是「不覆盖」，不是「保证正确」。
     *
     * @param metaObject 当前操作的实体元信息，由 MyBatis-Plus 在插入前传入；
     *                   内部包装的是真正的实体对象，所以填充会直接改到那个对象上，
     *                   方法返回后调用方拿到的实体就已经带上时间了。不要传 null，
     *                   那会在反射取值时抛空指针
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
     * 更新时填充更新人与更新时间，创建人/创建时间绝不触碰
     *
     * <p>为什么这样设计：审计的核心价值是「创建信息不可篡改」。
     * 如果更新时也顺手填一遍 createdBy，那么谁最后改了这条数据，
     * 创建人就变成了谁——数据看起来没问题，追溯链却已经断了。
     * 所以这里只填两个 updated 开头的字段，这是刻意的「不完整」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>填 updatedAt：每次更新都刷新为当前时刻，这是「最近修改时间」的唯一来源；</li>
     *     <li>填 updatedBy：同样先用 0L 占位，接入鉴权后换成当前登录用户。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只在走 MyBatis-Plus 的 update 方法（实体更新或 Wrapper 更新）时触发。
     * 手写 XML 的自定义 SQL、或直接用 JdbcTemplate 执行 UPDATE，都不会经过这里，
     * 那两个字段需要自己维护。症状是「某些更新操作后修改时间不动」，
     * 排查时容易怀疑到本类头上，其实压根没走到。
     *
     * @param metaObject 当前操作的实体元信息，由 MyBatis-Plus 在更新前传入。
     *                   注意用 Wrapper 更新且没有传实体对象时，metaObject 里可能没有对应属性，
     *                   此时填充会被静默跳过，不会抛异常
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, 0L);
    }
}

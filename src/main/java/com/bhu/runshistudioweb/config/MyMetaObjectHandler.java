package com.bhu.runshistudioweb.config;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
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
 * 「人」从 Sa-Token 登录态取：恒写 0 等于每条数据都署名「系统」，
 * 内容出了事故既查不到人，也无法区分「人改的」与「定时任务改的」。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 系统操作人 ID
     *
     * 未登录（定时任务、启动自检、单元测试）时写它。与操作日志表 operator_id = 0 同一口径：
     * 「0 = 没有具体的人」，而不是「某个 ID 为 0 的用户」。
     */
    private static final long SYSTEM_USER_ID = 0L;

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
     * 例如 TrafficServiceImpl 显式写 0L 表示「埋点由系统写入」，那是业务语义，本类不应改写。
     *
     * @param metaObject 当前操作的实体元信息，内部包装的是真正的实体对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // strictInsertFill 只在字段当前为 null 时才填充：
        // 业务方显式赋值（例如数据迁移要保留原始创建时间）时不会被覆盖
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());

        // 创建人 / 更新人：取当前登录用户 ID，未登录或拿不到上下文时记为系统（0）
        Long operatorId = currentUserIdOrSystem();
        this.strictInsertFill(metaObject, "createdBy", Long.class, operatorId);
        this.strictInsertFill(metaObject, "updatedBy", Long.class, operatorId);
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
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, currentUserIdOrSystem());
    }

    /**
     * 当前登录用户 ID；没有登录态时回退为系统（0）
     *
     * 必须 catch 住所有异常：定时任务、MQ 消费、启动自检这类线程没有 Sa-Token 请求上下文，
     * {@code StpUtil.isLogin()} 会抛 SaTokenContextException。
     * 「填不了审计字段」是运维问题，「主业务被打挂」是事故——两者不能混为一谈。
     *
     * @return 登录用户 ID；未登录或无上下文时返回 0
     */
    private Long currentUserIdOrSystem() {
        try {
            if (!StpUtil.isLogin()) {
                return SYSTEM_USER_ID;
            }
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            // 只记 debug：批量写入时每次都要回退，打成 warn 会刷屏
            log.debug("无法获取当前登录用户，审计字段记为系统 | 原因={}", e.getMessage());
            return SYSTEM_USER_ID;
        }
    }
}

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
 * @author shaoshing
 */
@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 系统操作人 ID */
    private static final long SYSTEM_USER_ID = 0L;

    /** 插入时填充创建人 / 创建时间与更新人 / 更新时间 */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());

        // 创建人 / 更新人：取当前登录用户 ID，未登录或拿不到上下文时记为系统（0）
        Long operatorId = currentUserIdOrSystem();
        this.strictInsertFill(metaObject, "createdBy", Long.class, operatorId);
        this.strictInsertFill(metaObject, "updatedBy", Long.class, operatorId);
    }

    /** 更新时填充更新人与更新时间，创建人 / 创建时间绝不触碰 */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, currentUserIdOrSystem());
    }

    private Long currentUserIdOrSystem() {
        try {
            if (!StpUtil.isLogin()) {
                return SYSTEM_USER_ID;
            }
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            log.debug("无法获取当前登录用户，审计字段记为系统 | 原因={}", e.getMessage());
            return SYSTEM_USER_ID;
        }
    }
}

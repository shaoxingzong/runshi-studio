-- ============================================================================================================
-- 单表建表脚本：sys_user
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `sys_user`;

CREATE TABLE `sys_user` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_account` varchar(64) NOT NULL COMMENT '登录账号',
    `user_password` varchar(128) NOT NULL COMMENT '密码（BCrypt/Argon2 哈希串，留足余量）',
    `user_name` varchar(64) NOT NULL COMMENT '真实姓名/昵称',
    `user_avatar` varchar(512) DEFAULT NULL COMMENT '头像 URL',
    `user_role` varchar(32) NOT NULL DEFAULT 'user' COMMENT '权限角色：user-普通注册用户, member-工作室成员, admin-管理员（取值须与 StpInterfaceImpl 及 @SaCheckRole 入参三处一致）',
    `user_status` tinyint NOT NULL DEFAULT '0' COMMENT '账号状态：0-正常, 1-封禁',
    `phone` varchar(20) DEFAULT NULL COMMENT '手机号（未填写时必须存 NULL，禁止存空字符串）',
    `email` varchar(128) DEFAULT NULL COMMENT '邮箱（未填写时必须存 NULL，禁止存空字符串）',
    `ai_query_count` int NOT NULL DEFAULT '0' COMMENT 'AI 累计提问次数（DB 基准值；未落库的那部分在 Redis，读取时合并）',
    `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
    `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
    PRIMARY KEY (`id`),
    -- 三个唯一键都带 deleted_at：删除账号后，账号名/手机号/邮箱应能被重新注册使用
    UNIQUE KEY `uk_account_deleted` (`user_account`, `deleted_at`),
    UNIQUE KEY `uk_phone_deleted` (`phone`, `deleted_at`),
    UNIQUE KEY `uk_email_deleted` (`email`, `deleted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统用户表';

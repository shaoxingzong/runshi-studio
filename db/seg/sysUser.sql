-- ============================================================================================================
-- 润石工作室官网 · sys_user 系统用户表（从 db/user.sql 拆分而来）
-- ============================================================================================================
--
-- 适用场景：只需要建「系统用户」这一张表时单独执行本脚本（可重复执行：先 DROP 再 CREATE，⚠️ 会清空数据）。
-- 执行方式：
--   ① mysql 客户端：mysql -uroot -p123456 < db/sys_user.sql
--   ② 全量建库请直接执行 db/user.sql（它已包含本表，两者内容保持一致，改结构记得同步两处）。
--
-- 设计约定（与 user.sql 全局一致）：
--   1) 主键统一用雪花算法 bigint（MyBatis-Plus ASSIGN_ID），**不自增**——便于分库分表、可提前生成 ID。
--   2) 逻辑删除用 deleted_at（毫秒时间戳，0=未删）；因此**所有唯一键都必须带上 deleted_at**，
--      否则删除后的账号名/手机号会被唯一键永久占用，无法重建同名数据。
--   3) 审计四列 created_at / updated_at / created_by / updated_by 由 MyMetaObjectHandler 自动填充，业务代码不手写。
--   4) 全文 utf8mb4 + utf8mb4_unicode_ci：中文排序与 emoji 都不会乱码。
--   5) **没有任何数据库外键**：关联关系全部由应用层（MyBatis-Plus）维护。
-- ============================================================================================================

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

-- 保证注释里的中文按 utf8mb4 写入（mysql 客户端默认可能按其它字符集发送）
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `sys_user`;

-- ============================================================================================================
-- 系统用户表
-- ============================================================================================================
-- 登录账号与权限。**角色只是权限标签**：能否发帖/签到/无限用 AI 一律以 studio_member 档案为准，
-- 不要把业务能力判断挂在 user_role 上（见 StudioMemberServiceImpl / PostServiceImpl 的判定）。
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

-- ============================================================================================================
-- 初始管理员（刻意不内置，与 db/user.sql 保持一致）：前台注册一个账号后
--   UPDATE sys_user SET user_role = 'admin' WHERE user_account = '你的账号' AND deleted_at = 0;
-- 然后立即改密。完整步骤见仓库根目录 DEPLOY.md。
-- ============================================================================================================
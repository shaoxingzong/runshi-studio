-- ============================================================================================================
-- 单表建表脚本：studio_ai_session
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_ai_session`;

CREATE TABLE `studio_ai_session` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint DEFAULT NULL COMMENT '提问用户 ID（关联 sys_user.id）；新会话必须登录创建，历史数据可能遗留 NULL（早期匿名会话）',
    `title` varchar(64) DEFAULT NULL COMMENT '会话标题（取首问前 30 字，仅用于会话列表展示）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间（每次追加消息都会刷新，用于按最近活跃排序）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 「我的会话」按最近活跃倒序
    KEY `idx_user_updated` (`user_id`, `updated_at` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 咨询会话表';

-- ============================================================================================================
-- 单表建表脚本：studio_post_like
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_post_like`;

CREATE TABLE `studio_post_like` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `post_id` bigint NOT NULL COMMENT '帖子 ID（关联 studio_post.id）',
    `user_id` bigint NOT NULL COMMENT '点赞用户 ID（关联 sys_user.id；必须登录后才能点赞）',
    `created_at` datetime NOT NULL COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
    -- 「我赞过哪些帖子」按时间倒序，为将来做「我的点赞列表」预留
    KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '帖子点赞表';

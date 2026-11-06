-- ============================================================================================================
-- 单表建表脚本：studio_member_project
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_member_project`;

CREATE TABLE `studio_member_project` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
    `project_id` bigint NOT NULL COMMENT '项目 ID（关联 studio_project.id）',
    `created_at` datetime NOT NULL COMMENT '关联建立时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_proj` (`member_id`, `project_id`),
    -- 反查「某项目的全部成员」（项目详情页的参与成员列表）
    KEY `idx_proj_member` (`project_id`, `member_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '成员-项目关联表';

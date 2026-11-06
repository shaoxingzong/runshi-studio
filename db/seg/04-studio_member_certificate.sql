-- ============================================================================================================
-- 单表建表脚本：studio_member_certificate
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_member_certificate`;

CREATE TABLE `studio_member_certificate` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
    `certificate_id` bigint NOT NULL COMMENT '证书 ID（关联 studio_certificate.id）',
    `created_at` datetime NOT NULL COMMENT '关联建立时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_cert` (`member_id`, `certificate_id`),
    -- 反查「某证书被哪些成员持有」
    KEY `idx_cert_member` (`certificate_id`, `member_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '成员-证书关联表';

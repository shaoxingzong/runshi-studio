-- ============================================================================================================
-- 单表建表脚本：studio_certificate
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_certificate`;

CREATE TABLE `studio_certificate` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '证书/获奖名称',
    `award_level` varchar(32) NOT NULL COMMENT '级别维度：national-国家级, provincial-省级, municipal-市级/校级',
    `award_type` varchar(32) NOT NULL COMMENT '类型维度：competition-学科竞赛, soft_copyright-软著, patent-专利, paper-论文',
    `award_date` date NOT NULL COMMENT '获奖/颁发日期',
    `image_url` varchar(512) NOT NULL COMMENT '证书图片 URL（站内 /uploads 路径，上传后写入）',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 两个索引分别服务官网的「按类型倒序」「按级别倒序」筛选
    KEY `idx_type_date` (`award_type`, `award_date` DESC),
    KEY `idx_level_date` (`award_level`, `award_date` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '荣誉证书表';

-- ============================================================================================================
-- 单表建表脚本：studio_project
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_project`;

CREATE TABLE `studio_project` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '项目名称',
    `cover_image` varchar(512) DEFAULT NULL COMMENT '项目封面 URL（站内 /uploads 路径）',
    `description` varchar(512) NOT NULL COMMENT '项目摘要',
    `content` text COMMENT '项目详情（Markdown 正文，RAG 切分数据源）',
    `tech_stack` varchar(256) DEFAULT NULL COMMENT '技术栈标签（JSON 数组快照，如 ["Spring Boot","MySQL"]）',
    `demo_url` varchar(256) DEFAULT NULL COMMENT '在线体验地址',
    `github_url` varchar(256) DEFAULT NULL COMMENT '开源仓库地址',
    `leader_id` bigint NOT NULL COMMENT '项目队长 ID（权威数据源，关联 studio_member.id）',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '项目状态：0-研发中, 1-已上线, 2-已结题',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 官网列表：按状态过滤 → 权重倒序 → 时间倒序
    KEY `idx_status_sort_time` (`status`, `sort_order` DESC, `created_at` DESC),
    KEY `idx_leader` (`leader_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '项目案例表';

-- ============================================================================================================
-- 单表建表脚本：studio_board_item
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_board_item`;

CREATE TABLE `studio_board_item` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '事项标题',
    `description` text COMMENT '事项说明（Markdown；前端渲染前必须清洗，禁止 v-html 原文）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待办, 1-进行中, 2-已完成（须与 BoardItemStatusEnum 一致）',
    `assignee_member_id` bigint DEFAULT NULL COMMENT '负责人成员档案 ID（关联 studio_member.id；NULL=未指派）',
    `deadline` datetime DEFAULT NULL COMMENT '截止日期时间；NULL=不设截止。过期只做高亮，不自动改状态',
    `completed_at` datetime DEFAULT NULL COMMENT '完成时间（进入已完成时写入，回退时清空）',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '同列内展示权重，越大越靠前',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 看板主查询：按状态分列，列内按截止日期升序（近的在前）
    KEY `idx_status_deadline` (`status`, `deadline`, `deleted_at`),
    -- 「谁还没交」：按负责人筛未完成项
    KEY `idx_assignee_status` (`assignee_member_id`, `status`, `deleted_at`),
    -- 逾期统计：全表扫 deadline 时走索引
    KEY `idx_deadline` (`deadline`, `deleted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT = '工作室内部事项看板（仅成员可见）';

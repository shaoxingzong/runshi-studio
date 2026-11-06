-- ============================================================================================================
-- 单表建表脚本：studio_page_visit
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_page_visit`;

CREATE TABLE `studio_page_visit` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `page_path` varchar(256) NOT NULL COMMENT '页面路径（如 /posts、/projects/123；不含 query）',
    `visitor_id` varchar(64) NOT NULL COMMENT '访客标识（前端 localStorage 随机 uuid；UV 去重依据，非设备指纹）',
    `user_id` bigint DEFAULT NULL COMMENT '登录用户 ID（关联 sys_user.id；未登录为 NULL，由后端从登录态取）',
    `referrer_host` varchar(256) DEFAULT NULL COMMENT '来源域名（外站跳转进来才有值；站内跳转不记）',
    `device_type` varchar(16) DEFAULT NULL COMMENT '设备类型：pc / mobile（按 UA 粗分，仅作参考维度）',
    `created_at` datetime NOT NULL COMMENT '访问时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（匿名埋点填 0）',
    PRIMARY KEY (`id`),
    -- 每日趋势 / 时间段统计：按页 + 时间
    KEY `idx_path_time` (`page_path`, `created_at`),
    -- UV 去重（COUNT(DISTINCT visitor_id)）与「回头访客」分析
    KEY `idx_visitor_time` (`visitor_id`, `created_at`),
    -- 归档清理（DELETE WHERE created_at < ?）：没有它每天凌晨都会全表扫描
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门户页面访问记录表（流量统计）';

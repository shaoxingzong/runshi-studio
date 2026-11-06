-- ============================================================================================================
-- 单表建表脚本：studio_post_report
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_post_report`;

CREATE TABLE `studio_post_report` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `target_type` varchar(16) NOT NULL COMMENT '举报目标类型：post-帖子, comment-评论（取值须与 ReportTargetTypeEnum 一致）',
    `target_id` bigint NOT NULL COMMENT '举报目标 ID（指向对应业务表主键）',
    `reporter_id` bigint NOT NULL COMMENT '举报人 ID（关联 sys_user.id）',
    `reason` varchar(256) NOT NULL COMMENT '举报理由（举报人填写）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '处理状态：0-待处理, 1-已处置, 2-举报不成立（取值须与 ReportStatusEnum 一致）',
    `handle_by` bigint DEFAULT NULL COMMENT '处理人 ID（管理员）',
    `handle_at` datetime DEFAULT NULL COMMENT '处理时间',
    `handle_result` varchar(256) DEFAULT NULL COMMENT '处理结果说明（给内部复盘用）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 带 deleted_at：撤销举报后允许再次举报同一内容
    UNIQUE KEY `uk_target_reporter` (`target_type`, `target_id`, `reporter_id`, `deleted_at`),
    KEY `idx_status_time` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '内容举报表';

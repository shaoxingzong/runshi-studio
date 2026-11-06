-- ============================================================================================================
-- 单表建表脚本：studio_notification
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_notification`;

CREATE TABLE `studio_notification` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `receiver_id` bigint NOT NULL COMMENT '接收者用户 ID（关联 sys_user.id）',
    `type` varchar(32) NOT NULL COMMENT '通知类型：POST_APPROVED/POST_REJECTED/COMMENT_REPLIED/POST_COMMENTED/POST_LIKED/MEMBER_GRANTED/REPORT_RESOLVED/REPORT_DISMISSED，须与 NotificationTypeEnum 一致',
    `biz_type` varchar(32) DEFAULT NULL COMMENT '来源业务类型：POST/COMMENT/USER',
    `biz_id` bigint DEFAULT NULL COMMENT '来源业务 ID（点击跳转用）',
    `title` varchar(128) NOT NULL COMMENT '通知标题（一句话摘要，快照不随业务变化）',
    `content` varchar(512) NOT NULL COMMENT '通知正文（快照；驳回类带理由）',
    `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读：0-未读, 1-已读',
    `read_at` datetime DEFAULT NULL COMMENT '已读时间',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 幂等：同一事件重复投递撞键即已知存在。必须带 deleted_at（唯一键铁律）
    UNIQUE KEY `uk_receiver_biz` (`receiver_id`, `type`, `biz_type`, `biz_id`, `deleted_at`),
    -- 列表（receiver + is_read 等值过滤 + 创建时间倒序）与未读计数一条索引全覆盖；
    -- created_at 进索引是为了让 ORDER BY 不走 filesort
    KEY `idx_receiver_read` (`receiver_id`, `is_read`, `deleted_at`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '站内通知表';

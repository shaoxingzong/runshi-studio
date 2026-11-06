-- ============================================================================================================
-- 单表建表脚本：studio_post_comment
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_post_comment`;

CREATE TABLE `studio_post_comment` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `post_id` bigint NOT NULL COMMENT '所属帖子 ID（关联 studio_post.id）',
    `author_id` bigint NOT NULL COMMENT '评论人 ID（关联 sys_user.id；登录用户即可评论，不限成员）',
    `parent_id` bigint DEFAULT NULL COMMENT '父评论 ID（楼中楼）；NULL 表示直接评论帖子',
    `content` varchar(1000) NOT NULL COMMENT '评论正文（纯文本，最长 1000 字符）',
    `floor` int DEFAULT NULL COMMENT '楼层号（顶层评论从 1 递增；回复某条评论时为 NULL）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审, 1-已通过, 2-已驳回（与帖子共用 AuditStatusEnum）',
    `reject_reason` varchar(256) DEFAULT NULL COMMENT '驳回理由（给用户看；仅 status=2 时有意义）',
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    `ai_audit_status` tinyint NOT NULL DEFAULT '0' COMMENT 'AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)',
    `ai_reason` varchar(256) DEFAULT NULL COMMENT 'AI 给出的理由（管理端展示用；给用户看的驳回理由另存 reject_reason）',
    `ai_at` datetime DEFAULT NULL COMMENT 'AI 判定时（未判则为 NULL）',
    `deleted_by` bigint DEFAULT NULL COMMENT '删除人 ID（管理员删除评论时的审计字段）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 详情页最高频：按帖子查已通过评论。第三列放 id 让「按 id 排序」直接走索引顺序
    KEY `idx_post_status_id` (`post_id`, `status`, `id`),
    KEY `idx_status_time` (`status`, `created_at`),
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '帖子评论表（楼中楼）';

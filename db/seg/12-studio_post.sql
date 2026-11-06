-- ============================================================================================================
-- 单表建表脚本：studio_post
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_post`;

CREATE TABLE `studio_post` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '帖子标题',
    `summary` varchar(512) DEFAULT NULL COMMENT '摘要（列表展示；为空时由正文前若干字兜底，不在读取时现算）',
    `cover_image` varchar(512) DEFAULT NULL COMMENT '封面图 URL（列表缩略图；入库时自动取正文首图，可覆盖）',
    `content` text NOT NULL COMMENT '正文（Markdown；图片内嵌其中，不另建图片表）',
    `author_id` bigint NOT NULL COMMENT '发帖人 ID（关联 sys_user.id；只有绑定的在队成员能发帖）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审, 1-已通过, 2-已驳回（取值须与 AuditStatusEnum 一致）',
    `reject_reason` varchar(256) DEFAULT NULL COMMENT '驳回理由（给用户看；仅 status=2 时有意义）',
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员；AI 自动处置时留空，不伪造管理员身份）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    `ai_audit_status` tinyint NOT NULL DEFAULT '0' COMMENT 'AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)',
    `ai_reason` varchar(256) DEFAULT NULL COMMENT 'AI 给出的理由（管理端展示用；给用户看的驳回理由另存 reject_reason）',
    `ai_at` datetime DEFAULT NULL COMMENT 'AI 判定时（未判则为 NULL）',
    `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
    `comment_count` int NOT NULL DEFAULT '0' COMMENT '评论数（冗余，避免列表页 N+1）',
    `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数（冗余列，避免列表页 N+1；只能赞不能取消，只增不减）',
    `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶：0-否, 1-是',
    `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
    `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
    PRIMARY KEY (`id`),
    -- ① C 端列表：置顶优先 + 时间倒序
    KEY `idx_status_pinned_time` (`status`, `pinned`, `created_at`),
    -- ② 审核队列：只按时间倒序。**不能复用 ①**：第二列是 pinned，优化器跳不过中间列
    KEY `idx_status_time` (`status`, `created_at`),
    -- ③ 「我的发帖」
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '帖子表';

-- ============================================================================================================
-- 单表建表脚本：studio_quiz_wrong
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_quiz_wrong`;

CREATE TABLE `studio_quiz_wrong` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint NOT NULL COMMENT '用户 ID（关联 sys_user.id）',
    `question_id` bigint NOT NULL COMMENT '题目 ID（关联 studio_quiz_question.id）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待复习, 1-已消解；取值须与 QuizWrongStatusEnum 一致',
    `wrong_count` int NOT NULL DEFAULT '1' COMMENT '累计答错次数（Integer 不用 Long：计数要走数字序列化）',
    `correct_streak` int NOT NULL DEFAULT '0' COMMENT '消解进度：连续答对次数（达到阈值即自动消解）',
    `last_wrong_at` datetime NOT NULL COMMENT '最近答错时间（错题列表排序键）',
    `resolved_at` datetime DEFAULT NULL COMMENT '消解时间（重新入本时清空）',
    `resolve_type` tinyint DEFAULT NULL COMMENT '消解方式：1-连对达标（自动）, 2-手动标记',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_question` (`user_id`, `question_id`, `deleted_at`),
    KEY `idx_user_status_time` (`user_id`, `status`, `last_wrong_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '刷题错题本表';

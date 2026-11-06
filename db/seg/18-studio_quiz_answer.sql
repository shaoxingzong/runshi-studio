-- ============================================================================================================
-- 单表建表脚本：studio_quiz_answer
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_quiz_answer`;

CREATE TABLE `studio_quiz_answer` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint NOT NULL COMMENT '作答人 ID（关联 sys_user.id）',
    `batch_id` bigint NOT NULL COMMENT '批次 ID（开批时服务端用 IdWorker 生成，只用于幂等与分组）',
    `question_id` bigint NOT NULL COMMENT '题目 ID（关联 studio_quiz_question.id）',
    `chosen_labels` varchar(8) DEFAULT NULL COMMENT '所选标签（升序无分隔）："AC"；跳过时为 NULL（空值存 NULL，禁止存空串）',
    `correct_labels` varchar(8) NOT NULL COMMENT '提交时刻的正确标签**快照**（改题不影响历史判定的可解释性）',
    `result` tinyint NOT NULL COMMENT '结果：1-正确, 2-错误, 3-跳过（只有 2 会写错题本）；取值须与 QuizAnswerResultEnum 一致',
    `created_at` datetime NOT NULL COMMENT '发生时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间（本表无更新路径，恒等于 created_at）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 幂等的根基：同一批里同一题只能落一条（双击 / 断网重试撞这个键）
    UNIQUE KEY `uk_user_batch_question` (`user_id`, `batch_id`, `question_id`, `deleted_at`),
    -- 概览统计（按用户 + 结果）
    KEY `idx_user_result` (`user_id`, `result`, `id`),
    -- 顺序模式「已作答」判定 / 错题回顾取最近一条
    KEY `idx_user_question` (`user_id`, `question_id`),
    -- 管理端错误率榜（按题目聚合）
    KEY `idx_question_result` (`question_id`, `result`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '刷题作答记录表（错题本的真相源）';

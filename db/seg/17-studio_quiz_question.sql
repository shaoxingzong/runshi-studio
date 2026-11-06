-- ============================================================================================================
-- 单表建表脚本：studio_quiz_question
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_quiz_question`;

CREATE TABLE `studio_quiz_question` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `textbook_name` varchar(128) NOT NULL COMMENT '教材名称（章节不建表，归属用名字列表达）',
    `textbook_seq` int NOT NULL DEFAULT '0' COMMENT '教材顺序：数值越小越靠前',
    `chapter_name` varchar(128) NOT NULL COMMENT '章节名称（如：第一章 世界的物质性及发展规律）',
    `chapter_seq` int NOT NULL DEFAULT '0' COMMENT '章节在教材内的顺序：数值越小越靠前',
    `type` tinyint NOT NULL DEFAULT '0' COMMENT '题型：0-单选（恰好 1 个正确项）, 1-多选（至少 2 个，全对才得分）；取值须与 QuizQuestionTypeEnum 一致',
    `stem` varchar(2000) NOT NULL COMMENT '题干（纯文本，不做 Markdown 渲染）',
    `options` text NOT NULL COMMENT '选项 JSON 数组：[{"label":"A","content":"运动性"}]；只含展示数据，不含正确性',
    `correct_labels` varchar(8) NOT NULL COMMENT '正确选项标签（升序、无分隔）：单选 "B"，多选 "ABD"',
    `analysis` varchar(2000) DEFAULT NULL COMMENT '解析（只在作答之后下发）',
    `difficulty` tinyint NOT NULL DEFAULT '1' COMMENT '难度：1-易, 2-中, 3-难',
    `source` varchar(128) DEFAULT NULL COMMENT '题源（如：2023年真题 / 自编）',
    `question_seq` int NOT NULL DEFAULT '0' COMMENT '题序：数值越小越靠前（即教材顺序）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-启用, 1-停用（停用即下线；历史作答保留）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除）',
    PRIMARY KEY (`id`),
    -- 抽题 / 章节列表 / 章节题数：教材 → 章节 → 状态 → 题序
    KEY `idx_scope` (`textbook_name`, `chapter_name`, `status`, `question_seq`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '刷题题目表（题目/选项/答案/章节归属合一）';

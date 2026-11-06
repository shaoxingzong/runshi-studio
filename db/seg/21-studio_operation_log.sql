-- ============================================================================================================
-- 单表建表脚本：studio_operation_log
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_operation_log`;

CREATE TABLE `studio_operation_log` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `operator_id` bigint NOT NULL DEFAULT '0' COMMENT '操作人 ID（sys_user.id；0=系统/未登录，正常管理操作不应为 0）',
    `operator_account` varchar(64) NOT NULL DEFAULT '' COMMENT '操作人账号快照（账号删除后仍可读）',
    `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人昵称快照',
    `action` varchar(48) NOT NULL COMMENT '动作码，须与 OperationTypeEnum 的 code 一致（如 POST_REJECT）',
    `target_type` varchar(32) NOT NULL COMMENT '对象类型：POST/COMMENT/USER/QUIZ，须与 OperationTypeEnum 一致',
    `target_id` bigint DEFAULT NULL COMMENT '对象 ID；章节/教材等无独立 ID 的动作为 NULL',
    `target_title` varchar(256) DEFAULT NULL COMMENT '对象标题快照（帖子标题 / 题目题干摘要 / 用户名等）',
    `detail` varchar(1000) DEFAULT NULL COMMENT '处置细节（驳回理由、变更项）；禁止写入口令与正文',
    `ip` varchar(45) DEFAULT NULL COMMENT '来源 IP（IPv6 最长 45 字符）；取不到为 NULL',
    `created_at` datetime NOT NULL COMMENT '发生时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间（本表无更新路径，实际恒等于 created_at）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 默认视图：按时间倒序翻页。id 一起进索引是为了同一秒内的多行也有稳定顺序
    KEY `idx_created` (`created_at`, `id`),
    -- 「这个管理员最近都干了什么」
    KEY `idx_operator_created` (`operator_id`, `created_at`),
    -- 「这类动作最近发生过哪些」
    KEY `idx_action_created` (`action`, `created_at`),
    -- 「这条内容被谁处置过」（事故反查的主路径）
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理端操作日志表（审计留痕）';

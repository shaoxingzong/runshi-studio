-- ============================================================================================================
-- 单表建表脚本：studio_member
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_member`;

CREATE TABLE `studio_member` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint DEFAULT NULL COMMENT '绑定的系统用户 ID（关联 sys_user.id）；门户「工作台 / 发帖 / 签到」都靠它识别成员',
    `name` varchar(64) NOT NULL COMMENT '成员姓名',
    `avatar` varchar(512) DEFAULT NULL COMMENT '成员照片 URL',
    `grade_year` smallint NOT NULL COMMENT '入学年份（如：2022）',
    `major` varchar(128) DEFAULT NULL COMMENT '专业',
    `direction` varchar(128) DEFAULT NULL COMMENT '技术方向（如：Java后端, AI应用）',
    `team_position` varchar(32) NOT NULL DEFAULT 'member' COMMENT '团队职务：member-成员, leader-队长, tech_lead-组长',
    `member_status` tinyint NOT NULL DEFAULT '0' COMMENT '成员状态：0-在读/在队, 1-毕业/离队',
    `github_url` varchar(256) DEFAULT NULL COMMENT 'GitHub 主页',
    `summary` varchar(512) DEFAULT NULL COMMENT '个人简介',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重（数值越大越靠前）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 一个账号最多绑定一份在档档案；带 deleted_at 才能「解绑后重新绑同一个人」
    UNIQUE KEY `uk_userid_deleted` (`user_id`, `deleted_at`),
    KEY `idx_grade_status` (`grade_year`, `member_status`, `sort_order`),
    KEY `idx_direction` (`direction`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '工作室成员档案表';

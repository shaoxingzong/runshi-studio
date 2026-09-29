CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `studio_db`;

-- 1. 系统用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
                            `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                            `user_account` varchar(64) NOT NULL COMMENT '登录账号',
                            `user_password` varchar(128) NOT NULL COMMENT '密码（BCrypt/Argon2 哈希串，留足余量）',
                            `user_name` varchar(64) NOT NULL COMMENT '真实姓名/昵称',
                            `user_avatar` varchar(512) DEFAULT NULL COMMENT '头像 URL',
                            `user_role` varchar(32) NOT NULL DEFAULT 'user' COMMENT '权限角色：user-普通注册用户, member-工作室成员, admin-管理员（取值须与 StpInterfaceImpl 及 @SaCheckRole 入参三处一致）',
                            `user_status` tinyint NOT NULL DEFAULT '0' COMMENT '账号状态：0-正常, 1-封禁',
                            `phone` varchar(20) DEFAULT NULL COMMENT '手机号（未填写时必须存 NULL，禁止存空字符串）',
                            `email` varchar(128) DEFAULT NULL COMMENT '邮箱（未填写时必须存 NULL，禁止存空字符串）',
                            `ai_query_count` int NOT NULL DEFAULT '0' COMMENT 'AI 累计提问次数',
                            `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
                            `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
                            `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
                            `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
                            `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
                            PRIMARY KEY (`id`),
                            UNIQUE KEY `uk_account_deleted` (`user_account`, `deleted_at`),
                            UNIQUE KEY `uk_phone_deleted` (`phone`, `deleted_at`),
                            UNIQUE KEY `uk_email_deleted` (`email`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 2. 工作室成员档案表
DROP TABLE IF EXISTS `studio_member`;
CREATE TABLE `studio_member` (
                                 `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                 `user_id` bigint DEFAULT NULL COMMENT '绑定的系统用户 ID（关联 sys_user.id）',
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
                                 UNIQUE KEY `uk_userid_deleted` (`user_id`, `deleted_at`),
                                 KEY `idx_grade_status` (`grade_year`, `member_status`, `sort_order`),
                                 KEY `idx_direction` (`direction`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作室成员档案表';

-- 3. 荣誉证书主表
DROP TABLE IF EXISTS `studio_certificate`;
CREATE TABLE `studio_certificate` (
                                      `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                      `title` varchar(128) NOT NULL COMMENT '证书/获奖名称',
                                      `award_level` varchar(32) NOT NULL COMMENT '级别维度：national-国家级, provincial-省级, municipal-市级/校级',
                                      `award_type` varchar(32) NOT NULL COMMENT '类型维度：competition-学科竞赛, soft_copyright-软著, patent-专利, paper-论文',
                                      `award_date` date NOT NULL COMMENT '获奖/颁发日期',
                                      `image_url` varchar(512) NOT NULL COMMENT '证书图片 URL',
                                      `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重',
                                      `created_at` datetime NOT NULL COMMENT '创建时间',
                                      `updated_at` datetime NOT NULL COMMENT '更新时间',
                                      `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                      `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                      `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                      PRIMARY KEY (`id`),
                                      KEY `idx_type_date` (`award_type`, `award_date` DESC),
                                      KEY `idx_level_date` (`award_level`, `award_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='荣誉证书表';

-- 4. 成员-证书关联表（物理删除）
DROP TABLE IF EXISTS `studio_member_certificate`;
CREATE TABLE `studio_member_certificate` (
                                             `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                             `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
                                             `certificate_id` bigint NOT NULL COMMENT '证书 ID（关联 studio_certificate.id）',
                                             `created_at` datetime NOT NULL COMMENT '创建时间',
                                             PRIMARY KEY (`id`),
                                             UNIQUE KEY `uk_member_cert` (`member_id`, `certificate_id`),
                                             KEY `idx_cert_member` (`certificate_id`, `member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员-证书关联表';

-- 5. 项目案例主表
DROP TABLE IF EXISTS `studio_project`;
CREATE TABLE `studio_project` (
                                  `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                  `title` varchar(128) NOT NULL COMMENT '项目名称',
                                  `cover_image` varchar(512) DEFAULT NULL COMMENT '项目封面 URL',
                                  `description` varchar(512) NOT NULL COMMENT '项目摘要',
                                  `content` text COMMENT '项目详情（Markdown 正文，RAG 切分数据源）',
                                  `tech_stack` varchar(256) DEFAULT NULL COMMENT '技术栈标签（JSON 数组快照）',
                                  `demo_url` varchar(256) DEFAULT NULL COMMENT '在线体验地址',
                                  `github_url` varchar(256) DEFAULT NULL COMMENT '开源仓库地址',
                                  `leader_id` bigint NOT NULL COMMENT '项目队长 ID（权威数据源，关联 studio_member.id）',
                                  `status` tinyint NOT NULL DEFAULT '1' COMMENT '项目状态：0-研发中, 1-已上线, 2-已结题',
                                  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重',
                                  `created_at` datetime NOT NULL COMMENT '创建时间',
                                  `updated_at` datetime NOT NULL COMMENT '更新时间',
                                  `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                  `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                  `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                  PRIMARY KEY (`id`),
                                  KEY `idx_status_sort_time` (`status`, `sort_order` DESC, `created_at` DESC),
                                  KEY `idx_leader` (`leader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目案例表';

-- 6. 成员-项目关联表（物理删除）
DROP TABLE IF EXISTS `studio_member_project`;
CREATE TABLE `studio_member_project` (
                                         `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                         `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
                                         `project_id` bigint NOT NULL COMMENT '项目 ID（关联 studio_project.id）',
                                         `created_at` datetime NOT NULL COMMENT '创建时间',
                                         PRIMARY KEY (`id`),
                                         UNIQUE KEY `uk_member_proj` (`member_id`, `project_id`),
                                         KEY `idx_proj_member` (`project_id`, `member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员-项目关联表';
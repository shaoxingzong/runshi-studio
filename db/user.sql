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

-- 7. AI 咨询会话表
-- user_id 可空是核心设计：NULL 表示匿名会话（游客），凭不可枚举的雪花 ID 即可续聊；
-- 非 NULL 则表示「绑定用户的会话，仅本人可续」。归属校验在应用层，见 AiChatServiceImpl。
DROP TABLE IF EXISTS `studio_ai_session`;
CREATE TABLE `studio_ai_session` (
                                     `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                     `user_id` bigint DEFAULT NULL COMMENT '提问用户 ID（关联 sys_user.id）；NULL 表示匿名会话（游客创建）',
                                     `title` varchar(64) DEFAULT NULL COMMENT '会话标题（取首问前 30 字，仅用于会话列表展示）',
                                     `created_at` datetime NOT NULL COMMENT '创建时间',
                                     `updated_at` datetime NOT NULL COMMENT '更新时间（每次追加消息都会刷新，用于按最近活跃排序）',
                                     `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                     `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                     `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                     PRIMARY KEY (`id`),
                                     KEY `idx_user_updated` (`user_id`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 咨询会话表';

-- 8. AI 咨询消息表
-- 注意 created_at 是秒级精度：同一秒内产生的 user / assistant 两条消息时间相同，
-- 因此所有查询都必须追加 id 作为次级排序键（雪花 ID 单调递增），否则对话记录会偶发颠倒。
DROP TABLE IF EXISTS `studio_ai_message`;
CREATE TABLE `studio_ai_message` (
                                     `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                     `session_id` bigint NOT NULL COMMENT '会话 ID（关联 studio_ai_session.id）',
                                     `role` varchar(16) NOT NULL COMMENT '消息角色：user-用户提问, assistant-AI 回答',
                                     `content` text NOT NULL COMMENT '消息正文（用户问题或 AI 回答原文）',
                                     `created_at` datetime NOT NULL COMMENT '创建时间',
                                     `updated_at` datetime NOT NULL COMMENT '更新时间',
                                     `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                     `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                     `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                     PRIMARY KEY (`id`),
                                     KEY `idx_session_created` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 咨询消息表';

-- 9. 知识库文档表（RAG 源文档的元数据与溯源）
-- 关键设计：**向量本体不在库里**。本表只存「这份文档是什么、来自哪、切了几块」，
-- 真正的向量存放在 LangChain4j 的 EmbeddingStore 中（本期内存实现，可平滑替换）。
-- source_id 是 R2 的溯源字段：检索命中后能反查到「答案是依据哪条业务数据生成的」。
-- 索引取舍：只为 (source_type, source_id) 建索引（增量同步时按来源定位文档）；
--   刻意**不为 status 建索引**——取值只有 3 个、选择性极差，
--   而「扫待处理文档」是后台批量任务，文档量级下全表扫描更快（见 DESIGN 3.3）。
DROP TABLE IF EXISTS `studio_knowledge_doc`;
CREATE TABLE `studio_knowledge_doc` (
                                        `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                        `title` varchar(128) NOT NULL COMMENT '文档标题（检索结果溯源展示用）',
                                        `source_type` varchar(16) NOT NULL COMMENT '来源类型：manual-手工录入, project-项目案例, member-成员档案, certificate-荣誉证书',
                                        `source_id` bigint DEFAULT NULL COMMENT '来源业务数据 ID（R2 溯源字段）：指向对应业务表主键；manual 来源为 NULL',
                                        `content_hash` varchar(64) NOT NULL COMMENT '正文内容哈希（增量更新去重：内容未变则跳过重建向量）',
                                        `status` tinyint NOT NULL DEFAULT '0' COMMENT '索引状态：0-待处理, 1-已索引, 2-失败',
                                        `chunk_count` int NOT NULL DEFAULT '0' COMMENT '已切分块数（与 studio_knowledge_chunk 的实际行数对齐）',
                                        `created_at` datetime NOT NULL COMMENT '创建时间',
                                        `updated_at` datetime NOT NULL COMMENT '更新时间',
                                        `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                        `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                        `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                        PRIMARY KEY (`id`),
                                        KEY `idx_source` (`source_type`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表（RAG 源文档元数据）';

-- 10. 知识库切分块表（RAG 切分块元数据）
-- 唯一索引带 deleted_at：整份文档重建时会先逻辑删除旧块再插新块，
-- 不带 deleted_at 的唯一键会让「重新索引」在第二次直接撞重复。
DROP TABLE IF EXISTS `studio_knowledge_chunk`;
CREATE TABLE `studio_knowledge_chunk` (
                                          `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                          `doc_id` bigint NOT NULL COMMENT '所属文档 ID（关联 studio_knowledge_doc.id）',
                                          `chunk_index` int NOT NULL COMMENT '块序号（同一文档内从 0 开始递增，决定拼接顺序）',
                                          `content` text NOT NULL COMMENT '块正文（检索命中后拼上下文与溯源展示用）',
                                          `embedding_id` varchar(128) DEFAULT NULL COMMENT '向量库条目 ID（LangChain4j EmbeddingStore 的条目标识，用于按向量反查文本）',
                                          `created_at` datetime NOT NULL COMMENT '创建时间',
                                          `updated_at` datetime NOT NULL COMMENT '更新时间',
                                          `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                          `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                          `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                          PRIMARY KEY (`id`),
                                          UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_index`, `deleted_at`),
                                          KEY `idx_embedding` (`embedding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库切分块表';

-- 11. 考勤签到记录表
-- 记录不可删：签到是审计凭证，逻辑删除字段只为「极端误操作由 DBA 手工处理」保留，
-- 应用层不提供任何删除 / 修改接口（见）。
-- attendance_date 是冗余列：由 check_in_at 按 Asia/Shanghai 派生后单独存一列，
-- 目的是让「按天查询」能走 idx_date_lan_time；若写成 DATE(check_in_at) 会导致索引失效。
DROP TABLE IF EXISTS `studio_attendance`;
CREATE TABLE `studio_attendance` (
                                          `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
                                          `user_id` bigint NOT NULL COMMENT '签到用户 ID（关联 sys_user.id；只有绑定账号的成员能签到）',
                                          `attendance_date` date NOT NULL COMMENT '签到日期（由 check_in_at 按 Asia/Shanghai 派生，冗余存储以便按天走索引）',
                                          `check_in_at` datetime NOT NULL COMMENT '签到时刻（Asia/Shanghai 本地时间）',
                                          `in_lan` tinyint NOT NULL DEFAULT '0' COMMENT '是否内网签到：0-外网，1-内网（由 ClientIpManager 按 lan-cidrs 判定）',
                                          `ip` varchar(64) NOT NULL COMMENT '来源 IP（归一化后；IPv6 最长 45 字符，留余量）',
                                          `user_agent` varchar(512) DEFAULT NULL COMMENT '客户端 UA（截断 512，仅作审计留痕，不用于风控）',
                                          `created_at` datetime NOT NULL COMMENT '创建时间',
                                          `updated_at` datetime NOT NULL COMMENT '更新时间',
                                          `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
                                          `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
                                          `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
                                          PRIMARY KEY (`id`),
                                          KEY `idx_user_time` (`user_id`, `check_in_at`),
                                          KEY `idx_date_lan_time` (`attendance_date`, `in_lan`, `check_in_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='考勤签到记录表';

-- 12. 帖子主表
-- 内容形态是「CSDN 式」：标题 + Markdown 正文，图片以 ![](url) 内嵌在 content 里，
-- 因此**不建图片关联表**——图片在正文流中的位置由 Markdown 决定，关联表表达不了。
-- 列表缩略图用 cover_image 单独存（入库时自动取正文首图）。
-- 增量脚本：db/migration/20261009_add_post.sql
DROP TABLE IF EXISTS `studio_post`;
CREATE TABLE `studio_post` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '帖子标题',
    `summary` varchar(512) DEFAULT NULL COMMENT '摘要（列表展示；为空时由正文前若干字兜底）',
    `cover_image` varchar(512) DEFAULT NULL COMMENT '封面图 URL（列表缩略图；入库时自动取正文首图，可覆盖）',
    `content` text NOT NULL COMMENT '正文（Markdown；图片内嵌其中，不另建图片表）',
    `author_id` bigint NOT NULL COMMENT '发帖人 ID（关联 sys_user.id；只有绑定的在队成员能发帖）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审, 1-已通过, 2-已驳回（取值须与 AuditStatusEnum 一致）',
    `reject_reason` varchar(256) DEFAULT NULL COMMENT '驳回理由（给用户看；仅 status=2 时有意义）',
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员）；AI 自动处置时留空（AI 不是人，不伪造管理员 ID）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    -- AI 审核三列：内容是「先审后发」，提交后由后台异步调模型初判，结论写在这里。
    -- 之所以要单独一列而不只靠 status：① 异步任务必须能识别「哪些还没判过」以保证幂等，
    --   否则重复送审会覆盖已有结论；②「还没轮到」（0）与「AI 判不了」（4）是两回事，
    --   混在一起的话管理端无法感知 AI 故障。取值须与 AiAuditStatusEnum 一致
    `ai_audit_status` tinyint NOT NULL DEFAULT '0' COMMENT 'AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)',
    `ai_reason` varchar(256) DEFAULT NULL COMMENT 'AI 给出的理由（管理端展示用；给用户看的驳回理由另存 reject_reason）',
    `ai_at` datetime DEFAULT NULL COMMENT 'AI 判定时（未判则为 NULL）',
    `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
    `comment_count` int NOT NULL DEFAULT '0' COMMENT '评论数（冗余，避免列表页 N+1）',
    `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶：0-否, 1-是',
    `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
    `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
    PRIMARY KEY (`id`),
    KEY `idx_status_pinned_time` (`status`, `pinned`, `created_at`),
    KEY `idx_status_time` (`status`, `created_at`),
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子表';

-- 13. 帖子评论表（楼中楼）
-- parent_id 为 NULL 表示直接评论帖子，非 NULL 表示回复某条评论（只支持两层）。
-- 读取时一次查完整个帖子的评论后在内存挂树，因此**不为 parent_id 建索引**——
-- 它只参与内存分组，不进 WHERE 条件。
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
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员）；AI 自动处置时留空（AI 不是人，不伪造管理员 ID）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    -- AI 审核三列（与 studio_post 同一套语义，取值须与 AiAuditStatusEnum 一致）
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
    KEY `idx_post_status_id` (`post_id`, `status`, `id`),
    KEY `idx_status_time` (`status`, `created_at`),
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子评论表（楼中楼）';

-- 14. 内容举报表
-- 举报是「免审内容」的兜底：被信任用户发布的内容自动通过、不经人工，仍需反馈通道。
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
    UNIQUE KEY `uk_target_reporter` (`target_type`, `target_id`, `reporter_id`, `deleted_at`),
    KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容举报表';
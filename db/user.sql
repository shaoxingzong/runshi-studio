-- ============================================================================================================
-- 润石工作室官网 · 全量建库脚本（从零初始化，唯一权威 DDL 源头）
-- ============================================================================================================
--
-- 适用场景：在**全新的空库**上一次性建好全部 20 张表。
-- 执行方式（任选其一）：
--   ① mysql 客户端：mysql -uroot -p123456 < db/user.sql
--   ② 已建好库：  mysql -uroot -p123456 studio_db < db/user.sql
-- 脚本可重复执行：先 DROP 再 CREATE，不会因「表已存在」报错（⚠️ 会清空数据）。
--
-- ⚠️ **改结构只改本文件**，然后手工在目标库上执行对应的 ALTER（并同步更新实体）——
--    增量迁移与全量脚本两条线并行，必然出现「改了其一忘了其二」的结构不一致。
--    旧库若确实需要按时间线升级，可用 git log 找回历史上的迁移脚本。
--
-- 设计约定（贯穿全部表，改表前必读）：
--   1) 主键统一用雪花算法 bigint（MyBatis-Plus ASSIGN_ID），**不自增**——便于分库分表、可提前生成 ID。
--   2) 逻辑删除用 deleted_at（毫秒时间戳，0=未删）；因此**所有唯一键都必须带上 deleted_at**，
--      否则删除后的账号名/手机号会被唯一键永久占用，无法重建同名数据。
--   3) 审计四列 created_at / updated_at / created_by / updated_by 由 MyMetaObjectHandler 自动填充，业务代码不手写。
--   4) 全文 utf8mb4 + utf8mb4_unicode_ci：中文排序与 emoji 都不会乱码。
--   5) **没有任何数据库外键**：关联关系全部由应用层（MyBatis-Plus）维护，
--      各表注释里的「关联 xxx.id」只是逻辑关系说明。好处是删改数据不受约束顺序限制、性能也更可控。
--
-- 目录（按依赖顺序，先父后子）：
--   1  sys_user                 系统用户（登录账号 / 角色）
--   2  studio_member            成员档案（与账号分离，靠 user_id 绑定）
--   3  studio_certificate       荣誉证书
--   4  studio_member_certificate  成员-证书关联（物理删除）
--   5  studio_project           项目案例
--   6  studio_member_project      成员-项目关联（物理删除）
--   7  studio_ai_session        AI 会话
--   8  studio_ai_message        AI 消息
--   9  studio_knowledge_doc     RAG 知识库文档
--  10  studio_knowledge_chunk   RAG 知识库切分块
--  11  studio_attendance        考勤签到
--  12  studio_post               帖子
--  13  studio_post_comment       帖子评论（楼中楼）
--  14  studio_post_report        内容举报
--  15  studio_post_like          帖子点赞
--  16  studio_page_visit         门户页面访问（流量统计）
--  17  studio_quiz_question      刷题题目（题目/选项/答案/章节归属合一）
--  18  studio_quiz_answer        刷题作答记录（错题本的真相源）
--  19  studio_quiz_wrong         刷题错题本（唯一有状态的表）
-- ============================================================================================================

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

-- 保证注释里的中文按 utf8mb4 写入（mysql 客户端默认可能按其它字符集发送）
SET NAMES utf8mb4;

-- ============================================================================================================
-- 一、先删后建：子表在前、父表在后
--     （本项目没有外键约束，顺序其实不影响执行；按依赖倒序写只是为了语义清晰、便于阅读）
-- ============================================================================================================
-- ⚠️ 新增表时这里必须同步加一条 DROP（按「先子后父」倒序）。
--    历史教训：studio_page_visit 作为第 16 张表后加进来时只加了 CREATE 没加 DROP，
--    导致重复执行本脚本时它报「already exists」并中断整个脚本——
--    「可重复执行」的承诺对漏掉的那张表是假的。
DROP TABLE IF EXISTS `studio_notification`;
DROP TABLE IF EXISTS `studio_quiz_wrong`;
DROP TABLE IF EXISTS `studio_quiz_answer`;
DROP TABLE IF EXISTS `studio_quiz_question`;
DROP TABLE IF EXISTS `studio_page_visit`;
DROP TABLE IF EXISTS `studio_post_like`;
DROP TABLE IF EXISTS `studio_post_report`;
DROP TABLE IF EXISTS `studio_post_comment`;
DROP TABLE IF EXISTS `studio_post`;
DROP TABLE IF EXISTS `studio_attendance`;
DROP TABLE IF EXISTS `studio_knowledge_chunk`;
DROP TABLE IF EXISTS `studio_knowledge_doc`;
DROP TABLE IF EXISTS `studio_ai_message`;
DROP TABLE IF EXISTS `studio_ai_session`;
DROP TABLE IF EXISTS `studio_member_project`;
DROP TABLE IF EXISTS `studio_member_certificate`;
DROP TABLE IF EXISTS `studio_project`;
DROP TABLE IF EXISTS `studio_certificate`;
DROP TABLE IF EXISTS `studio_member`;
DROP TABLE IF EXISTS `sys_user`;

-- ============================================================================================================
-- 1. 系统用户表
-- ============================================================================================================
-- 登录账号与权限。**角色只是权限标签**：能否发帖/签到/无限用 AI 一律以 studio_member 档案为准，
-- 不要把业务能力判断挂在 user_role 上（见 StudioMemberServiceImpl / PostServiceImpl 的判定）。
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
    `ai_query_count` int NOT NULL DEFAULT '0' COMMENT 'AI 累计提问次数（DB 基准值；未落库的那部分在 Redis，读取时合并）',
    `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
    `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
    PRIMARY KEY (`id`),
    -- 三个唯一键都带 deleted_at：删除账号后，账号名/手机号/邮箱应能被重新注册使用
    UNIQUE KEY `uk_account_deleted` (`user_account`, `deleted_at`),
    UNIQUE KEY `uk_phone_deleted` (`phone`, `deleted_at`),
    UNIQUE KEY `uk_email_deleted` (`email`, `deleted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统用户表';

-- ============================================================================================================
-- 2. 工作室成员档案表
-- ============================================================================================================
-- 与登录账号**分离**：可以有账号没档案（普通注册用户），也可以有档案没账号（历史导入）。
-- 「是否为成员」的唯一判据是本表 user_id 命中且 member_status=0，与 user_role 无关。
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

-- ============================================================================================================
-- 3. 荣誉证书表
-- ============================================================================================================
-- 级别 / 类型是**闭集枚举**（后端 CertificateAddRequest 会校验非法值 → A0401），
-- 官网荣誉墙与后台表格都按这两个维度做筛选与统计。
CREATE TABLE `studio_certificate` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '证书/获奖名称',
    `award_level` varchar(32) NOT NULL COMMENT '级别维度：national-国家级, provincial-省级, municipal-市级/校级',
    `award_type` varchar(32) NOT NULL COMMENT '类型维度：competition-学科竞赛, soft_copyright-软著, patent-专利, paper-论文',
    `award_date` date NOT NULL COMMENT '获奖/颁发日期',
    `image_url` varchar(512) NOT NULL COMMENT '证书图片 URL（站内 /uploads 路径，上传后写入）',
    `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示置顶权重',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 两个索引分别服务官网的「按类型倒序」「按级别倒序」筛选
    KEY `idx_type_date` (`award_type`, `award_date` DESC),
    KEY `idx_level_date` (`award_level`, `award_date` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '荣誉证书表';

-- ============================================================================================================
-- 4. 成员-证书关联表（物理删除）
-- ============================================================================================================
-- 与 studio_member_project / studio_post_like 同属一类「关系表」：
-- **物理删除、无 deleted_at、无审计四列**——只有 id / 两个外键 / created_at。
-- 理由：关系一旦解除即无业务意义，不需要追溯历史版本；留 deleted_at 只会让唯一键和查询都多带一个条件。
-- ⚠️ 对应的实体类（StudioMemberCertificate 等）也**只能**声明这 4 个字段，
--    多写一个字段就会让 MP 生成带不存在列的 SQL，直接报 Unknown column。
CREATE TABLE `studio_member_certificate` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
    `certificate_id` bigint NOT NULL COMMENT '证书 ID（关联 studio_certificate.id）',
    `created_at` datetime NOT NULL COMMENT '关联建立时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_cert` (`member_id`, `certificate_id`),
    -- 反查「某证书被哪些成员持有」
    KEY `idx_cert_member` (`certificate_id`, `member_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '成员-证书关联表';

-- ============================================================================================================
-- 5. 项目案例表
-- ============================================================================================================
-- leader_id 是**权威数据源**：后端建/改项目时保证队长必然出现在 studio_member_project 里，
-- 因此前端可以直接渲染队长，不必先查关联再判断。
CREATE TABLE `studio_project` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '项目名称',
    `cover_image` varchar(512) DEFAULT NULL COMMENT '项目封面 URL（站内 /uploads 路径）',
    `description` varchar(512) NOT NULL COMMENT '项目摘要',
    `content` text COMMENT '项目详情（Markdown 正文，RAG 切分数据源）',
    `tech_stack` varchar(256) DEFAULT NULL COMMENT '技术栈标签（JSON 数组快照，如 ["Spring Boot","MySQL"]）',
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
    -- 官网列表：按状态过滤 → 权重倒序 → 时间倒序
    KEY `idx_status_sort_time` (`status`, `sort_order` DESC, `created_at` DESC),
    KEY `idx_leader` (`leader_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '项目案例表';

-- ============================================================================================================
-- 6. 成员-项目关联表（物理删除）
-- ============================================================================================================
-- 规则同第 4 张表：只有 4 列、无逻辑删除、无审计列。
CREATE TABLE `studio_member_project` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `member_id` bigint NOT NULL COMMENT '成员 ID（关联 studio_member.id）',
    `project_id` bigint NOT NULL COMMENT '项目 ID（关联 studio_project.id）',
    `created_at` datetime NOT NULL COMMENT '关联建立时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_proj` (`member_id`, `project_id`),
    -- 反查「某项目的全部成员」（项目详情页的参与成员列表）
    KEY `idx_proj_member` (`project_id`, `member_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '成员-项目关联表';

-- ============================================================================================================
-- 7. AI 咨询会话表
-- ============================================================================================================
-- user_id 仍**可空**是历史原因：早期 AI 对游客开放，库里可能遗留匿名会话（user_id = NULL）。
-- 现在的规则是「AI 必须登录才能用」（见 AiChatServiceImpl.prepareChat 的强制登录校验），
-- 新会话不会再出现 NULL；保留可空 + 不写死 NOT NULL，是为了兼容旧数据、避免 ALTER 失败。
CREATE TABLE `studio_ai_session` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint DEFAULT NULL COMMENT '提问用户 ID（关联 sys_user.id）；新会话必须登录创建，历史数据可能遗留 NULL（早期匿名会话）',
    `title` varchar(64) DEFAULT NULL COMMENT '会话标题（取首问前 30 字，仅用于会话列表展示）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间（每次追加消息都会刷新，用于按最近活跃排序）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 「我的会话」按最近活跃倒序
    KEY `idx_user_updated` (`user_id`, `updated_at` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 咨询会话表';

-- ============================================================================================================
-- 8. AI 咨询消息表
-- ============================================================================================================
-- 只存 user / assistant 两种角色的原文，不存 system prompt（那是代码里的模板，不该落库）。
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI 咨询消息表';

-- ============================================================================================================
-- 9. 知识库文档表（RAG 源文档元数据）
-- ============================================================================================================
-- source_type + source_id 是**多态来源**：project / member / certificate 的业务数据变化时增量同步，
-- manual 为手工录入（source_id = NULL）。content_hash 用于「内容没变就跳过重建向量」。
-- ⚠️ 刻意不为 status 建索引：只有 3 个取值、选择性极差，扫待处理文档是后台批量任务，全表扫描更快。
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文档表（RAG 源文档元数据）';

-- ============================================================================================================
-- 10. 知识库切分块表
-- ============================================================================================================
-- 向量本体**不在库里**：embedding_id 只是向量库（LangChain4j EmbeddingStore）的条目标识，
-- 将来换向量库不用改表结构。
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
    -- 唯一键同样必须带 deleted_at：否则重建索引（删旧块+插新块）第二次会撞重复
    UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_index`, `deleted_at`),
    KEY `idx_embedding` (`embedding_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库切分块表';

-- ============================================================================================================
-- 11. 考勤签到记录表
-- ============================================================================================================
-- 签到**只对绑定账号的在队成员开放**（后端按 studio_member 判定，不看 user_role）。
-- attendance_date 是冗余列：写成 DATE(check_in_at) 会让索引失效，单独存一列才能走索引。
-- 逻辑删除只为 DBA 兜底，应用层不提供删改接口。
CREATE TABLE `studio_attendance` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `user_id` bigint NOT NULL COMMENT '签到用户 ID（关联 sys_user.id；只有绑定账号的成员能签到）',
    `attendance_date` date NOT NULL COMMENT '签到日期（由 check_in_at 按 Asia/Shanghai 派生，冗余存储以便按天走索引）',
    `check_in_at` datetime NOT NULL COMMENT '签到时刻（Asia/Shanghai 本地时间）',
    `in_lan` tinyint NOT NULL DEFAULT '0' COMMENT '是否内网签到：0-外网, 1-内网（由 ClientIpManager 按 lan-cidrs 判定）',
    `ip` varchar(64) NOT NULL COMMENT '来源 IP（归一化后；IPv6 最长 45 字符，留余量）',
    `user_agent` varchar(512) DEFAULT NULL COMMENT '客户端 UA（截断 512，仅作审计留痕，不用于风控）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `check_in_at`),
    -- 「今日出勤看板」：按天查 + 区分内外网
    KEY `idx_date_lan_time` (`attendance_date`, `in_lan`, `check_in_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '考勤签到记录表';

-- ============================================================================================================
-- 12. 帖子表
-- ============================================================================================================
-- 「先审后发」：提交后 status=0 待审，只有 status=1 对公众可见。
-- 图片**内嵌在 content 的 Markdown 里**，不另建图片表——post_id + url + sort_order 表达不了
-- 「图在第几段之后」，而 Markdown 自身可以。cover_image 单独存（入库时自动取正文首图，可覆盖）。
-- AI 审核三列（ai_audit_status / ai_reason / ai_at）而非只靠 status：
--   ① 异步任务要能识别「哪些还没判过」以保证幂等，否则重复送审会覆盖已有结论；
--   ② 「还没轮到」（0）与「AI 判不了」（4）是两回事，混在一起管理端感知不到 AI 故障。
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

-- ============================================================================================================
-- 13. 帖子评论表（楼中楼）
-- ============================================================================================================
-- 只做两层：parent_id 自引用，NULL 表示直接评论帖子。
-- 读取时一次查完整个帖子的评论、在内存挂树，因此**不为 parent_id 建索引**（它只参与内存分组，不进 WHERE）。
-- floor 只在顶层评论递增；登录用户即可评论，不限成员。
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

-- ============================================================================================================
-- 14. 内容举报表
-- ============================================================================================================
-- target_type + target_id 是**多态目标**（post / comment）。没有拆成两张举报表：
-- 举报字段完全一致，拆开只会让「举报列表」这类查询要 UNION。
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
    -- 带 deleted_at：撤销举报后允许再次举报同一内容
    UNIQUE KEY `uk_target_reporter` (`target_type`, `target_id`, `reporter_id`, `deleted_at`),
    KEY `idx_status_time` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '内容举报表';

-- ============================================================================================================
-- 15. 帖子点赞表（物理删除）
-- ============================================================================================================
-- 规则同第 4 / 6 张关系表：只有 4 列、无 deleted_at、无审计列。
-- 业务上**只能赞、不能取消**（没有 unlike 接口），不存在「解除点赞」的场景，所以是物理删除。
-- uk_post_user 是「一人一赞」的根基：应用层先查重，该唯一键兜住并发下的重复插入
-- （两个请求同时通过查重时，第二个会撞键失败，由 Service 按幂等返回）。
CREATE TABLE `studio_post_like` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `post_id` bigint NOT NULL COMMENT '帖子 ID（关联 studio_post.id）',
    `user_id` bigint NOT NULL COMMENT '点赞用户 ID（关联 sys_user.id；必须登录后才能点赞）',
    `created_at` datetime NOT NULL COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user` (`post_id`, `user_id`),
    -- 「我赞过哪些帖子」按时间倒序，为将来做「我的点赞列表」预留
    KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '帖子点赞表';

-- ============================================================================================================
-- 16. 门户页面访问记录表（流量统计埋点）
-- ============================================================================================================
-- 为什么单独建表而不是用日志：流量要能「查」（PV/UV、每日趋势、热门页面），
-- 日志只能「看」—— grep 不出来报表，也聚不了合。
--
-- 埋点方式：前端路由变化时调 POST /traffic/report（匿名可访问，游客也要统计），
-- 后端从登录态取 user_id（未登录为 NULL），**不信前端传的用户身份**。
--
-- ⚠️ 隐私口径（刻意为之，改动前想清楚）：
--   - 记 visitor_id（前端 localStorage 里的随机 uuid）而**不记 IP/UA**：
--     UV 去重只需要一个稳定标识，IP 属于个人数据，多存一份多一分泄露面；
--   - referrer 只存**域名**不存完整 URL——完整 URL 的 query 可能带 token；
--   - page_path 同样**不含 query**（分页参数、搜索词都不记），
--     既避免 token 泄露，也让「/posts 第 1 页」和「第 2 页」算同一个页面。
--
-- ⚠️ 本表只增不改不删：**没有 deleted_at**（访问记录没有「改」和「删」的场景，
-- 加了只会让每个查询多带一个条件），也没有 updated_by。
-- 代价是数据只涨——量级到百万行后应按 created_at 归档/分区（DDL 注释即约定，
-- 本期不做自动清理，见 Service 的同类注释）。
CREATE TABLE `studio_page_visit` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `page_path` varchar(256) NOT NULL COMMENT '页面路径（如 /posts、/projects/123；不含 query）',
    `visitor_id` varchar(64) NOT NULL COMMENT '访客标识（前端 localStorage 随机 uuid；UV 去重依据，非设备指纹）',
    `user_id` bigint DEFAULT NULL COMMENT '登录用户 ID（关联 sys_user.id；未登录为 NULL，由后端从登录态取）',
    `referrer_host` varchar(256) DEFAULT NULL COMMENT '来源域名（外站跳转进来才有值；站内跳转不记）',
    `device_type` varchar(16) DEFAULT NULL COMMENT '设备类型：pc / mobile（按 UA 粗分，仅作参考维度）',
    `created_at` datetime NOT NULL COMMENT '访问时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（匿名埋点填 0）',
    PRIMARY KEY (`id`),
    -- 每日趋势 / 时间段统计：按页 + 时间
    KEY `idx_path_time` (`page_path`, `created_at`),
    -- UV 去重（COUNT(DISTINCT visitor_id)）与「回头访客」分析
    KEY `idx_visitor_time` (`visitor_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '门户页面访问记录表（流量统计）';

-- ============================================================================================================
-- 17. 刷题题目表（题目 / 选项 / 答案 / 章节归属合一）
-- ============================================================================================================
-- 三个决定（改表前先读）：
--   1) **选项存 JSON，不建选项表**：选项没有独立生命周期，改题就是整批重写；
--      从属数据单独建表只会多一个 Mapper、一套级联删除、一次 N+1。
--   2) **options 列只存展示数据**（label + content），正确性单独放 correct_labels ——
--      于是 C 端 VO 可以把 options 原样下发，不存在「忘了过滤 isCorrect」的泄露面。
--      脱敏靠**数据结构**，不靠「记得删字段」（HELP.md 第 10 条同一思路）。
--   3) **章节不建表**：教材名 / 章节名就是四个列，章节列表用 GROUP BY 出。
--      章节只因题目存在而存在，空章节没有业务意义。
--
-- ⚠️ 三个 *_seq 的语义与全站 sort_order **相反**：这里是「数值越小越靠前」（教材顺序），
--    而 sort_order 是「数值越大越靠前」（置顶权重）。同名会踩雷，故另起名。
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

-- ============================================================================================================
-- 18. 作答记录表（审计凭证，append-only）
-- ============================================================================================================
-- 跳过也落一条（result=3）：否则断点续答无法区分「处理过」与「还没轮到」，幂等也没法做；
-- 但跳过**不碰错题本**、响应里**不含答案**（否则跳过按钮就成了看答案按钮，顺序模式会被刷穿）。
--
-- batch_id 只是幂等键与「本轮」分组，**没有对应的会话表**：
-- 题目本身不含答案，整批由接口一次性返回、前端持有（sessionStorage），泄露不了。
-- 本表无更新路径：updated_at 恒等于 created_at，字段保留只为十张表结构统一。
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

-- ============================================================================================================
-- 19. 错题本表（唯一有状态的表）
-- ============================================================================================================
-- 为什么它必须是一张表，而不是从作答记录派生：
--   correct_streak（连对进度）、手动消解、「消解后再错要重开」都是**状态**，派生不出来；
--   用窗口函数现算会让每次查错题本都重算一遍，且「连对 2 次」这类规则没法用一条 SQL 表达清楚。
--
-- 唯一键带 deleted_at 是全项目铁律：否则「移出错题本后再答错」无法重新入本。
-- 「题目被停用/删除」时**不动本表**，由查询侧过滤（inSql 子查询），
-- 这样停用是可逆的——重新启用后错题条目自动回来。
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

-- ============================================================================================================
-- 20. 站内通知表
-- ============================================================================================================
-- 为什么需要它：系统里有大量「人对人的动作」（审核结论、评论回复、身份授予），
-- 但此前用户没有任何感知渠道——被驳回的作者只能自己去「我的发帖」翻。
-- 这张表补上反馈回路，是产品闭环的一部分，不是锦上添花。
--
-- 设计要点：
--   1) title/content **冗余**在表里，不存模板 ID 去拼：通知是「快照」——
--      业务数据后来变了（帖子改名、评论删除），已发出的通知文字不该跟着变；
--   2) uk_receiver_biz 带 deleted_at 是项目唯一键铁律，同时承担**幂等**职责：
--      同一事件重复投递撞键即视为已投递（与帖子点赞 uk_post_user 同一套路）；
--   3) biz_type/biz_id 是「跳转指针」，前端按 type 决定跳到哪——
--      来源被删后指针悬空，前端按不存在处理即可，不影响通知本身的可读性。
CREATE TABLE `studio_notification` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `receiver_id` bigint NOT NULL COMMENT '接收者用户 ID（关联 sys_user.id）',
    `type` varchar(32) NOT NULL COMMENT '通知类型：POST_APPROVED/POST_REJECTED/COMMENT_REPLIED/MEMBER_GRANTED，须与 NotificationTypeEnum 一致',
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

-- ============================================================================================================
-- 附：初始管理员（刻意不内置，防弱口令上线）
-- ============================================================================================================
-- 刻意不内置管理员：脚本可重复执行且上线必跑，
-- 内置账号等于「上线即留下一个已知口令的 admin」——弱口令是安全事故，不是配置问题。
--
-- 生产初始化管理员的正确做法（完整步骤见仓库根目录 DEPLOY.md）：
--   ① 前台注册一个普通账号（/user/register）；
--   ② 在库里把它提升为管理员：
--        UPDATE sys_user SET user_role = 'admin' WHERE user_account = '你的账号' AND deleted_at = 0;
--   ③ 立即改密（后台「用户管理 → 重置密码」），并使用强口令。
-- ⚠️ 不要在 Git 仓库里提交任何带口令的 INSERT——哈希可复用不等于口令可公开。
-- ============================================================================================================

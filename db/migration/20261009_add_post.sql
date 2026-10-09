-- ============================================================
-- 增量迁移：新增「帖子 / 评论 / 举报」三张表（第 12、13、14 张表）
--
-- 适用对象：生产环境（已经跑着前 11 张表的库）
-- 执行方式：mysql -u<账号> -p<密码> studio_db < db/migration/20261009_add_post.sql
--
-- 幂等性：三句都用 CREATE TABLE IF NOT EXISTS，索引一律写在建表语句内部，
--         因此重复执行时整句被跳过，不会报错也不会重复建索引。
--         ⚠️ MySQL 没有 CREATE INDEX IF NOT EXISTS，所以「索引写在建表语句内」
--         是幂等的前提——不要把它们拆成独立的 CREATE INDEX 语句。
--         ⚠️ 本脚本**不 DROP 任何对象**，绝不丢数据（与 db/user.sql 不同，
--         那个是从零初始化用的，允许 DROP）。
--
-- 对应 DDL 源头：db/user.sql 第 12、13、14 张表
-- ============================================================

-- ============================================================
-- 12. 帖子主表
--
-- 内容形态是「CSDN 式」：标题 + Markdown 正文，图片以 ![](url) 形式**内嵌在 content 里**，
-- 因此本模块**刻意不建图片关联表**——图片在正文流中的位置由 Markdown 决定，
-- 关联表（post_id + url + sort_order）表达不了「图在第几段之后」，建了也没用。
-- 列表页需要的缩略图单独用 cover_image 存（保存时自动取正文首图，可人工覆盖）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `studio_post` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '帖子标题',
    `summary` varchar(512) DEFAULT NULL COMMENT '摘要（列表展示；为空时由正文前若干字兜底，不在读取时现算）',
    `cover_image` varchar(512) DEFAULT NULL COMMENT '封面图 URL（列表缩略图；入库时自动取正文首图，可覆盖）',
    `content` text NOT NULL COMMENT '正文（Markdown；图片内嵌其中，不另建图片表）',
    `author_id` bigint NOT NULL COMMENT '发帖人 ID（关联 sys_user.id；只有绑定的在队成员能发帖）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审, 1-已通过, 2-已驳回（取值须与 AuditStatusEnum 一致）',
    `reject_reason` varchar(256) DEFAULT NULL COMMENT '驳回理由（给用户看；仅 status=2 时有意义）',
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    `view_count` int NOT NULL DEFAULT '0' COMMENT '浏览量',
    -- comment_count 是冗余列：列表页要展示「多少条评论」，
    -- 若每次现算就是每帖一次 COUNT（标准 N+1，一页 20 帖 = 20 次查询）。
    -- 由评论审核通过 / 删除时维护，与 studio_attendance 的 attendance_date 是同一类取舍
    `comment_count` int NOT NULL DEFAULT '0' COMMENT '评论数（冗余，避免列表页 N+1）',
    `pinned` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶：0-否, 1-是',
    `created_at` datetime NOT NULL COMMENT '创建时间（MyBatis-Plus 自动填充）',
    `updated_at` datetime NOT NULL COMMENT '更新时间（MyBatis-Plus 自动填充）',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID（MyBatis-Plus 自动填充）',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID（MyBatis-Plus 自动填充）',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳（0-未删除，非0-已删除）',
    PRIMARY KEY (`id`),
    -- ① C 端列表：WHERE status=1 AND deleted_at=0 ORDER BY pinned DESC, created_at DESC
    --    把 pinned 放在 status 之后、created_at 之前，正好匹配这个排序，避免 filesort
    KEY `idx_status_pinned_time` (`status`, `pinned`, `created_at`),
    -- ② 审核队列：WHERE status=0 ORDER BY created_at（先进先出）。
    --    不能复用上面那条：它的第二列是 pinned，优化器无法跳过中间列直接按 created_at 排序，
    --    因此这条独立索引是必要的，不是冗余
    KEY `idx_status_time` (`status`, `created_at`),
    -- ③「我的发帖」（作者看自己的待审 / 驳回 / 已通过，按时间倒序）
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子表';

-- ============================================================
-- 13. 帖子评论表（楼中楼）
--
-- parent_id 为 NULL 表示「直接评论帖子」，非 NULL 表示回复某条评论。
-- 只支持两层的楼中楼：读取时一次查完整个帖子的评论，在内存里按 parent_id 挂成树，
-- 因此**不为 parent_id 建索引**——它只参与内存分组，不进 WHERE 条件，建了也用不上。
-- ============================================================
CREATE TABLE IF NOT EXISTS `studio_post_comment` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `post_id` bigint NOT NULL COMMENT '所属帖子 ID（关联 studio_post.id）',
    `author_id` bigint NOT NULL COMMENT '评论人 ID（关联 sys_user.id；登录用户即可评论，不限成员）',
    `parent_id` bigint DEFAULT NULL COMMENT '父评论 ID（楼中楼）；NULL 表示直接评论帖子',
    -- 正文用 varchar(1000) 而不是 text：评论是短文本，定长上限既能挡住超长输入，
    -- 也让「入库前校验长度」与「列宽」是同一个值，不会出现「代码限 1000、列却是 text」的错位
    `content` varchar(1000) NOT NULL COMMENT '评论正文（纯文本，最长 1000 字符）',
    `floor` int DEFAULT NULL COMMENT '楼层号（顶层评论从 1 递增；回复某条评论时为 NULL）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审, 1-已通过, 2-已驳回（与帖子共用 AuditStatusEnum）',
    `reject_reason` varchar(256) DEFAULT NULL COMMENT '驳回理由（给用户看；仅 status=2 时有意义）',
    `audit_by` bigint DEFAULT NULL COMMENT '审核人 ID（管理员）',
    `audit_at` datetime DEFAULT NULL COMMENT '审核时间',
    -- deleted_by 是审计字段：管理员删的是**别人的**内容，光有 deleted_at 只知道「什么时候删的」，
    -- 追溯不到「谁删的」。出争议时必须能查到操作人，故单独存一列
    `deleted_by` bigint DEFAULT NULL COMMENT '删除人 ID（管理员删除评论时的审计字段）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- ① 某帖的全部评论：WHERE post_id=? AND status=1 AND deleted_at=0 ORDER BY id
    --    这是详情页最高频的查询；第三列放 id 可以让排序直接走索引顺序（雪花 ID 递增 ≈ 时间递增），
    --    避免额外的排序步骤
    KEY `idx_post_status_id` (`post_id`, `status`, `id`),
    -- ② 评论待审队列（与帖子同一套路：先进先出）
    KEY `idx_status_time` (`status`, `created_at`),
    -- ③「我的评论」
    KEY `idx_author_time` (`author_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子评论表（楼中楼）';

-- ============================================================
-- 14. 内容举报表
--
-- 举报是「免审内容」的兜底：被信任的用户发布的内容会自动通过、不经人工，
-- 仍需给其他人一条反馈通道。举报成立后内容转待审 / 删除，不成立则保留。
-- ============================================================
CREATE TABLE IF NOT EXISTS `studio_post_report` (
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
    -- 唯一索引带上 deleted_at：与 uk_account_deleted / uk_userid_deleted 同一套路。
    -- 不带 deleted_at 的话，一条举报被删除后，同一人就无法再次举报同一内容了
    -- （唯一键冲突），这对「误举报后撤销、发现问题再举报」是真实存在的场景
    UNIQUE KEY `uk_target_reporter` (`target_type`, `target_id`, `reporter_id`, `deleted_at`),
    -- 管理员的待处理列表：WHERE status=0 ORDER BY created_at
    KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容举报表';

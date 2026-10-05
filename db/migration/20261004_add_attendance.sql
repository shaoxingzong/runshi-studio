-- ============================================================
-- 增量迁移：新增「考勤签到记录表」studio_attendance（第 11 张表）
--
-- 适用对象：生产环境（已经跑着前 10 张表的库）
-- 执行方式：mysql -u<账号> -p<密码> studio_db < db/migration/20261004_add_attendance.sql
--
-- 幂等性：整条语句用 CREATE TABLE IF NOT EXISTS，索引写在建表语句内部，
--         因此重复执行时整句被跳过、不会报错也不会重复建索引。
--         ⚠️ 本脚本**不 DROP 任何对象**，绝不丢数据（与 db/user.sql 的建库脚本不同，
--         那个是从零初始化用的，允许 DROP）。
--
-- 对应 DDL 源头：db/user.sql 第 11 张表；设计论证见 db/DESIGN.md 场景 N。
-- ============================================================

CREATE TABLE IF NOT EXISTS `studio_attendance` (
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

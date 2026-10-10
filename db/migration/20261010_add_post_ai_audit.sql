-- ============================================================
-- 增量迁移：帖子 / 评论表新增「AI 审核」三列
--
-- 适用对象：生产环境（已经跑着 20261009_add_post.sql 的库）
-- 执行方式：mysql -u<账号> -p<密码> studio_db < db/migration/20261010_add_post_ai_audit.sql
--
-- 背景：内容审核从「本地敏感词（AC 自动机）+ 全量人工」改为
--       「提交 → 后台异步调 AI 初判 → 三分法处置（安全放行 / 违规驳回 / 灰色转人工）」。
--       需要在两张内容表上记录 AI 判了什么，供管理端展示与幂等控制。
--
-- 幂等性：MySQL 没有 ALTER TABLE ... ADD COLUMN IF NOT EXISTS（那是 MariaDB 的语法），
--         因此用 information_schema 判断列是否存在、再用动态 SQL 决定是否 ALTER。
--         重复执行时整段会被跳过，不会报错也不会重复加列。
--         ⚠️ 唯一的 DROP 对象是**本脚本自己创建的**临时存储过程，
--         不碰任何业务对象、绝不丢数据（与 db/user.sql 不同，那个是从零初始化用的）。
--
-- 对应 DDL 源头：db/user.sql 第 12、13 张表
-- ============================================================

-- 幂等加列的辅助存储过程。
-- DELIMITER 是 mysql 客户端命令（不是 SQL 语句），因此本脚本必须按上面的方式
-- 用 mysql 客户端执行；换成 Flyway 之类不支持 DELIMITER 的工具时需要改写。
DROP PROCEDURE IF EXISTS `tmp_add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `tmp_add_column_if_missing`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition VARCHAR(1024)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 12. 帖子表
CALL tmp_add_column_if_missing('studio_post', 'ai_audit_status',
    "`ai_audit_status` tinyint NOT NULL DEFAULT '0' COMMENT 'AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)' AFTER `audit_at`");
CALL tmp_add_column_if_missing('studio_post', 'ai_reason',
    "`ai_reason` varchar(256) DEFAULT NULL COMMENT 'AI 给出的理由（管理端展示用；给用户看的驳回理由另存 reject_reason）' AFTER `ai_audit_status`");
CALL tmp_add_column_if_missing('studio_post', 'ai_at',
    "`ai_at` datetime DEFAULT NULL COMMENT 'AI 判定时（未判则为 NULL）' AFTER `ai_reason`");

-- 13. 帖子评论表
CALL tmp_add_column_if_missing('studio_post_comment', 'ai_audit_status',
    "`ai_audit_status` tinyint NOT NULL DEFAULT '0' COMMENT 'AI 审核结论：0-未判, 1-安全(自动通过), 2-违规(自动驳回), 3-灰色(转人工), 4-判定失败(转人工)' AFTER `audit_at`");
CALL tmp_add_column_if_missing('studio_post_comment', 'ai_reason',
    "`ai_reason` varchar(256) DEFAULT NULL COMMENT 'AI 给出的理由（管理端展示用；给用户看的驳回理由另存 reject_reason）' AFTER `ai_audit_status`");
CALL tmp_add_column_if_missing('studio_post_comment', 'ai_at',
    "`ai_at` datetime DEFAULT NULL COMMENT 'AI 判定时（未判则为 NULL）' AFTER `ai_reason`");

DROP PROCEDURE IF EXISTS `tmp_add_column_if_missing`;

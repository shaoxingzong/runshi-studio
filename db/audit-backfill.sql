-- ============================================================================================================
-- 审计列一次性回填（created_by / updated_by）
-- ============================================================================================================
--
-- 背景：MyMetaObjectHandler 曾经把 created_by / updated_by 硬编码为 0（当时还没有登录上下文），
-- 因此历史数据里这两列全是 0。代码已修（现在写当前登录人），但**存量数据不会自动变**，
-- 本脚本负责把「能由业务语义唯一确定」的归属补回去。
--
-- 执行方式（建议低峰期，先备份）：
--   mysql -uroot -p studio_db < db/audit-backfill.sql
--
-- 安全约定：
--   1) 只更新 created_by / updated_by 仍为 0 的行，**可重复执行**，不会覆盖已有值；
--   2) 只回填有明确「业务归属人」的表。无法确定的表（证书、项目、题目、知识库、通知、账号本身）
--      一律保持 0，并在文件末尾列出原因——编造一个"创建人"比留空更糟；
--   3) updated_by 只在「创建后从未修改过」（updated_at = created_at）时跟随 created_by：
--      修改过的行不知道中间是谁改的，写成创建人等于伪造审计链，宁可留 0；
--   4) 本脚本不改表结构、不删数据，也没有 DDL。
--
-- ⚠️ 若客户端开启了 safe-updates，批量 UPDATE 会被拒绝；本脚本显式关闭该会话开关。
-- ============================================================================================================

SET NAMES utf8mb4;
SET SQL_SAFE_UPDATES = 0;

-- ------------------------------------------------------------------------------------------------------------
-- 1. 创建人：按「业务归属人」回填
-- ------------------------------------------------------------------------------------------------------------

-- 帖子：作者就是创建人
UPDATE `studio_post`
SET `created_by` = `author_id`
WHERE `created_by` = 0
  AND `author_id` > 0;

-- 评论：作者就是创建人
UPDATE `studio_post_comment`
SET `created_by` = `author_id`
WHERE `created_by` = 0
  AND `author_id` > 0;

-- 举报：举报人就是创建人
UPDATE `studio_post_report`
SET `created_by` = `reporter_id`
WHERE `created_by` = 0
  AND `reporter_id` > 0;

-- 考勤：签到人就是创建人（此前提交时未登录的异常数据也会被补上）
UPDATE `studio_attendance`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` > 0;

-- 刷题作答：作答人就是创建人
UPDATE `studio_quiz_answer`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` > 0;

-- 错题本：所属用户就是创建人
UPDATE `studio_quiz_wrong`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` > 0;

-- AI 会话：发起人就是创建人
UPDATE `studio_ai_session`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` > 0;

-- AI 消息：消息本身没有 user_id，按所属会话反推
-- （会话已被删除时关联不到，保持 0；这是可接受的：消息的审计价值低于会话）
UPDATE `studio_ai_message` m
    JOIN `studio_ai_session` s ON m.`session_id` = s.`id`
SET m.`created_by` = s.`user_id`
WHERE m.`created_by` = 0
  AND s.`user_id` > 0;

-- 流量埋点：登录用户的访问记录；匿名的保持 0（系统写入，本来就无归属人）
UPDATE `studio_page_visit`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` IS NOT NULL
  AND `user_id` > 0;

-- 成员档案：绑定了登录账号的，档案由该账号建立
UPDATE `studio_member`
SET `created_by` = `user_id`
WHERE `created_by` = 0
  AND `user_id` IS NOT NULL
  AND `user_id` > 0;

-- 操作日志：操作人就是创建人
-- ⚠️ 该表是后加的（db/user.sql 第 21 张表）。若目标库还没跑过最新建库脚本，
--    这一段会报「表不存在」，跳过本条即可，其余回填不受影响。
UPDATE `studio_operation_log`
SET `created_by` = `operator_id`
WHERE `created_by` = 0
  AND `operator_id` > 0;

-- ------------------------------------------------------------------------------------------------------------
-- 2. 修改人：仅对「从未修改过」的行跟随创建人
-- ------------------------------------------------------------------------------------------------------------
-- 判定条件 updated_at = created_at 表示这一行自创建后没有走过后台修改路径。
-- 修改过的行保持 0：中间是谁改的已不可考，写成创建人会把审计链变成假的。
-- （AI 判定、计数自增这类系统写入本来就不刷新 updated_at，因此它们同样落在本条件内，
--   把 updated_by 记成内容归属人是可接受的近似。）

UPDATE `studio_post`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_post_comment`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_post_report`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_attendance`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_quiz_answer`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_quiz_wrong`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

UPDATE `studio_ai_session`
SET `updated_by` = `created_by`
WHERE `updated_by` = 0 AND `created_by` > 0 AND `updated_at` = `created_at`;

-- ------------------------------------------------------------------------------------------------------------
-- 3. 无法回填的表（保持 0，不是遗漏）
-- ------------------------------------------------------------------------------------------------------------
--   sys_user              账号自身的创建人：注册是自助行为，没有"操作人"可推；后台新增的历史记录也无从区分
--   studio_certificate    管理员录入，表里没有归属人字段
--   studio_project        同上（leader_id 是成员 ID，不是登录账号 ID，不能直接当 created_by）
--   studio_quiz_question  题库由管理员维护（多为脚本导入），没有归属人字段
--   studio_knowledge_doc  管理员/系统同步产生，无归属人字段
--   studio_knowledge_chunk 由文档切分产生，属于系统派生数据
--   studio_notification   系统投递：接收者不是创建人
--   studio_operation_log  已在第 1 节按 operator_id 回填
--
-- 这些表的后续写入会由 MyMetaObjectHandler 自动记录真实操作人；存量部分保留 0，
-- 含义是「系统/不可考」——比编造一个名字更诚实。
-- ============================================================================================================

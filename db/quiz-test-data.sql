-- ============================================================================================================
-- 刷题功能 · 一键测试数据
-- ============================================================================================================
-- 前置条件：已执行 db/quiz-seed-history.sql（题库需有「中国近现代史纲要」的题目）
--
-- 执行方式（任选其一）：
--   mysql -uroot -p studio_db < db/quiz-test-data.sql
--   或在已登录的 mysql 客户端里：source db/quiz-test-data.sql
--
-- 一键产出什么：
--   ① 两个账号（**口令不在仓库里**，导入后必须重置后才能登录）
--        test_admin   管理员，可进「后台 → 题库管理」
--        test_member  在队成员，可进「刷题」（普通注册用户进不去，认 studio_member 表）
--   ② 26 条作答记录（18 对 / 8 错，正确率约 69%），让「学习概览」四个格子都有数字
--   ③ 8 条错题：5 条待复习 + 3 条已消解，让错题本两种状态都能看到
--
-- 可重复执行：本脚本只操作固定 ID 段与固定账号名，每次执行前先删掉自己上次产生的数据，
--             因此重跑的结果与首次执行**完全一致**（不会累加、不会报唯一键冲突）。
-- 安全边界：删除条件严格限定在固定 ID 段（1930000000000xxxxxx）与 test_admin / test_member
--           两个账号名，绝不触碰真实数据。
-- ============================================================================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------------------------------------------------------
-- 0. 固定 ID 与口令哈希
-- ------------------------------------------------------------------------------------------------------------
-- 为什么 ID 要写死：可重复执行的前提是「每次产生同样的主键」，
-- 这样 DELETE 才能精确地把上次的数据清掉，而不会误伤真实数据。
--
-- 下面的哈希由项目自身的 PasswordUtils（Hutool BCrypt）生成并自校验过，
-- 但它的明文曾提交进本仓库，等价于**已经泄露**：
--   · 明文已从当前文件删除，且无法从哈希反推；
--   · 历史提交仍可翻到，所以唯一有效的处置是导入后立即重置这两个账号的口令
--     （用管理员账号在「用户管理 → 重置密码」里改；流程见 DEPLOY.md「口令轮换清单」）。
-- ⚠️ 这是测试账号，**不要**把本文件带上生产库执行。
SET @pwd_hash  = '$2a$10$X8QdCzZoJAdTWEjf.zuv4uakf.VdLALT50ak6gdIhoRwnH5C/A2ZG';
SET @admin_id  = 1930000000000000001;
SET @member_id = 1930000000000000002;
SET @admin_member_profile_id  = 1930000000000000011;
SET @member_member_profile_id = 1930000000000000012;
-- 教材名用「带排序规则的字符串字面量」而不是用户变量。
-- 原因（实测报错）：用户变量 @textbook 的字符集是连接字符集（utf8mb4_0900_ai_ci），
-- 与列上的 utf8mb4_unicode_ci 比较时会抛
-- 「Illegal mix of collations ... for operation '='」。
-- 写成 CONVERT(... USING utf8mb4) COLLATE utf8mb4_unicode_ci 后，比较两侧口径一致。
SET @textbook = CONVERT('中国近现代史纲要' USING utf8mb4) COLLATE utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------------------------------------
-- 1. 清理上一次生成的测试数据（只删自己）
-- ------------------------------------------------------------------------------------------------------------
-- 顺序：先删「引用方」，再删「被引用方」，避免留下孤儿行。
DELETE FROM `studio_quiz_wrong`
 WHERE `user_id` IN (@admin_id, @member_id)
   AND `id` >= 1930000000000002000 AND `id` < 1930000000000003000;

DELETE FROM `studio_quiz_answer`
 WHERE `user_id` IN (@admin_id, @member_id)
   AND `id` >= 1930000000000001000 AND `id` < 1930000000000002000;

DELETE FROM `studio_member`
 WHERE `id` IN (@admin_member_profile_id, @member_member_profile_id);

DELETE FROM `sys_user`
 WHERE `id` IN (@admin_id, @member_id)
    OR `user_account` IN ('test_admin', 'test_member');

-- ------------------------------------------------------------------------------------------------------------
-- 2. 账号与成员档案
-- ------------------------------------------------------------------------------------------------------------
-- 「成员」身份由 studio_member 档案决定，**不是** sys_user.user_role。
-- 所以 test_member 必须同时有账号（登录）和档案（准入），缺一进不了刷题页。
INSERT INTO `sys_user`
(`id`, `user_account`, `user_password`, `user_name`, `user_avatar`, `user_role`, `user_status`,
 `phone`, `email`, `ai_query_count`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
VALUES
(@admin_id,  'test_admin',  @pwd_hash, '测试管理员', NULL, 'admin',  0, NULL, NULL, 0, NOW(), NOW(), 0, 0, 0),
(@member_id, 'test_member', @pwd_hash, '测试成员',   NULL, 'member', 0, NULL, NULL, 0, NOW(), NOW(), 0, 0, 0);

INSERT INTO `studio_member`
(`id`, `user_id`, `name`, `avatar`, `grade_year`, `major`, `direction`, `team_position`,
 `member_status`, `github_url`, `summary`, `sort_order`, `created_at`, `updated_at`,
 `created_by`, `updated_by`, `deleted_at`)
VALUES
(@admin_member_profile_id,  @admin_id,  '测试管理员', NULL, 2023, '计算机科学与技术', 'Java后端', 'leader', 0,
 NULL, '刷题测试账号（管理员）', 100, NOW(), NOW(), 0, 0, 0),
(@member_member_profile_id, @member_id, '测试成员',   NULL, 2023, '计算机科学与技术', 'Java后端', 'member', 0,
 NULL, '刷题测试账号（成员）', 99,  NOW(), NOW(), 0, 0, 0);

-- ------------------------------------------------------------------------------------------------------------
-- 3. 作答记录：26 条
-- ------------------------------------------------------------------------------------------------------------
-- 取题顺序固定为 `ORDER BY id`（种子数据的 ID 与「章 → 题序」同序），
-- 于是「前 12 题」「第 13–20 题」在任何一次执行里都是同一批题。
--
-- 四批的设计意图：
--   batch 1（12 题）答对          → 建立基础正确率
--   batch 2（8 题）答错           → 这 8 题进入错题本
--   batch 3（3 题）再答对一次      → 错题本里连对进度 +1
--   batch 4（3 题）又答对一次      → 连对达到阈值 2，这 3 题自动消解
-- 最终：作答 26、答对 18、答错 8、正确率 69.2%、待复习 5、已消解 3。

-- 3.1 batch 1：12 题全部答对
INSERT INTO `studio_quiz_answer`
(`id`, `user_id`, `batch_id`, `question_id`, `chosen_labels`, `correct_labels`, `result`,
 `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
SELECT
  1930000000000001000 + ROW_NUMBER() OVER (ORDER BY q.`id`),
  @member_id,
  1930000000000000001,
  q.`id`,
  q.`correct_labels`,
  q.`correct_labels`,
  1,
  DATE_SUB(NOW(), INTERVAL 3 DAY),
  DATE_SUB(NOW(), INTERVAL 3 DAY),
  0, 0, 0
FROM (
  SELECT `id`, `correct_labels`
    FROM `studio_quiz_question`
   WHERE `deleted_at` = 0 AND `status` = 0 AND `textbook_name` = @textbook
   ORDER BY `id`
   LIMIT 12
) q;

-- 3.2 batch 2：8 题答错
--     故意选错的算法：`correct_labels = 'A'` 时选 B，否则选 A。
--     - 单选：无论正确项是什么，都必然选到另一个，判错；
--     - 多选：正确答案至少 2 个字母，只选 A 必然漏选，同样判错。
--     因此这个表达式对两种题型都成立，且 'A'/'B' 一定存在于 A–D 四个选项里。
INSERT INTO `studio_quiz_answer`
(`id`, `user_id`, `batch_id`, `question_id`, `chosen_labels`, `correct_labels`, `result`,
 `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
SELECT
  1930000000000001100 + ROW_NUMBER() OVER (ORDER BY q.`id`),
  @member_id,
  1930000000000000002,
  q.`id`,
  CASE WHEN q.`correct_labels` = 'A' THEN 'B' ELSE 'A' END,
  q.`correct_labels`,
  2,
  DATE_SUB(NOW(), INTERVAL 2 DAY),
  DATE_SUB(NOW(), INTERVAL 2 DAY),
  0, 0, 0
FROM (
  SELECT `id`, `correct_labels`
    FROM `studio_quiz_question`
   WHERE `deleted_at` = 0 AND `status` = 0 AND `textbook_name` = @textbook
   ORDER BY `id`
   LIMIT 8 OFFSET 12
) q;

-- 3.3 batch 3 / batch 4：让前 3 道错题各再答对一次 → 连对达到阈值 2 → 自动消解
INSERT INTO `studio_quiz_answer`
(`id`, `user_id`, `batch_id`, `question_id`, `chosen_labels`, `correct_labels`, `result`,
 `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
SELECT
  1930000000000001200 + ROW_NUMBER() OVER (ORDER BY q.`id`),
  @member_id,
  1930000000000000003,
  q.`id`,
  q.`correct_labels`,
  q.`correct_labels`,
  1,
  DATE_SUB(NOW(), INTERVAL 1 DAY),
  DATE_SUB(NOW(), INTERVAL 1 DAY),
  0, 0, 0
FROM (
  SELECT `id`, `correct_labels`
    FROM `studio_quiz_question`
   WHERE `deleted_at` = 0 AND `status` = 0 AND `textbook_name` = @textbook
   ORDER BY `id`
   LIMIT 3 OFFSET 12
) q;

INSERT INTO `studio_quiz_answer`
(`id`, `user_id`, `batch_id`, `question_id`, `chosen_labels`, `correct_labels`, `result`,
 `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
SELECT
  1930000000000001300 + ROW_NUMBER() OVER (ORDER BY q.`id`),
  @member_id,
  1930000000000000004,
  q.`id`,
  q.`correct_labels`,
  q.`correct_labels`,
  1,
  DATE_SUB(NOW(), INTERVAL 6 HOUR),
  DATE_SUB(NOW(), INTERVAL 6 HOUR),
  0, 0, 0
FROM (
  SELECT `id`, `correct_labels`
    FROM `studio_quiz_question`
   WHERE `deleted_at` = 0 AND `status` = 0 AND `textbook_name` = @textbook
   ORDER BY `id`
   LIMIT 3 OFFSET 12
) q;

-- ------------------------------------------------------------------------------------------------------------
-- 4. 错题本：8 条（5 待复习 + 3 已消解）
-- ------------------------------------------------------------------------------------------------------------
-- 与 batch 2 的 8 道错题一一对应。前 3 条对应 batch 3/4 已连对 2 次，故置为已消解；
-- 其余 5 条保持待复习。`last_wrong_at` 打散成不同时间，错题列表的「最久没复习优先」才看得出效果。
INSERT INTO `studio_quiz_wrong`
(`id`, `user_id`, `question_id`, `status`, `wrong_count`, `correct_streak`,
 `last_wrong_at`, `resolved_at`, `resolve_type`,
 `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted_at`)
SELECT
  1930000000000002000 + t.`rn`,
  @member_id,
  t.`id`,
  -- 前 3 条：已消解（1）
  IF(t.`rn` <= 3, 1, 0),
  1,
  -- 前 3 条：连对 2 次（达到 studio.quiz.resolve-streak 默认阈值）
  IF(t.`rn` <= 3, 2, 0),
  -- 答错时间各不相同：rn 越大越久远，让待复习列表有真实排序
  DATE_SUB(NOW(), INTERVAL (1 + t.`rn`) DAY),
  IF(t.`rn` <= 3, DATE_SUB(NOW(), INTERVAL 6 HOUR), NULL),
  IF(t.`rn` <= 3, 1, NULL),
  NOW(), NOW(), 0, 0, 0
FROM (
  SELECT `id`, ROW_NUMBER() OVER (ORDER BY `id`) AS `rn`
    FROM (
      SELECT `id`
        FROM `studio_quiz_question`
       WHERE `deleted_at` = 0 AND `status` = 0 AND `textbook_name` = @textbook
       ORDER BY `id`
       LIMIT 8 OFFSET 12
    ) x
) t;

-- ------------------------------------------------------------------------------------------------------------
-- 5. 执行结果自检（把数字打出来，一眼看出成没成）
-- ------------------------------------------------------------------------------------------------------------
SELECT '账号' AS `项目`, COUNT(*) AS `数量` FROM `sys_user`
 WHERE `user_account` IN ('test_admin', 'test_member') AND `deleted_at` = 0
UNION ALL
SELECT '成员档案', COUNT(*) FROM `studio_member`
 WHERE `id` IN (@admin_member_profile_id, @member_member_profile_id) AND `deleted_at` = 0
UNION ALL
SELECT '作答记录', COUNT(*) FROM `studio_quiz_answer`
 WHERE `user_id` = @member_id AND `deleted_at` = 0
UNION ALL
SELECT '错题本', COUNT(*) FROM `studio_quiz_wrong`
 WHERE `user_id` = @member_id AND `deleted_at` = 0;

-- 学习概览四格：应与页面显示一致
SELECT
  COUNT(*)                                                       AS `累计作答`,
  SUM(`result` = 1)                                              AS `答对`,
  CONCAT(ROUND(SUM(`result` = 1) / COUNT(*) * 100, 1), '%')      AS `正确率`
FROM `studio_quiz_answer`
WHERE `user_id` = @member_id AND `result` IN (1, 2) AND `deleted_at` = 0;

SELECT
  SUM(`status` = 0) AS `待复习错题`,
  SUM(`status` = 1) AS `已消解`
FROM `studio_quiz_wrong`
WHERE `user_id` = @member_id AND `deleted_at` = 0;

-- 提示（刻意不打印口令：脚本里没有，任何人也不该从仓库里知道）
SELECT '完成：先重置 test_member / test_admin 的口令，再分别登录「刷题」与「后台 → 题库管理」' AS `下一步`;

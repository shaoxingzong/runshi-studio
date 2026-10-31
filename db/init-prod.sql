-- ============================================================================================================
-- 润石工作室 · 生产库初始化（建库 + 建应用用户 + 授权 + 导入结构）
--
-- 适用场景：在**全新的空库**上一次性初始化。已在使用中的库不要跑——
-- 末尾会 source user.sql（先 DROP 再 CREATE，会清空数据）。
--
-- 执行方式（任意目录均可，本脚本无相对路径依赖）：
--   mysql -uroot -p < db/init-prod.sql
--
-- ⚠️ 执行前必改：下方 CREATE USER 语句里的占位口令。
--    口令不预先写死进 Git 仓库（本项目安全口径：任何带口令的语句都不入库），
--    所以这里放占位符，执行前手动改成真实口令；改完别把真实口令提交回仓库。
--
-- 与 db/user.sql 的分工：
--   user.sql        = 唯一 DDL 源头（21 张表的结构，改结构只改它）；
--                    自带建库与 USE，DROP+CREATE，只适合空库首建
--   init-prod.sql   = 「环境准备」：建库、建应用用户、授权（幂等，可重复执行）；
--                    不含结构导入（原因见第 ④ 条注释）
-- ============================================================================================================

-- ① 建库（与 user.sql 的建库语句保持一致：utf8mb4 + unicode_ci）
CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ② 应用用户（prod profile 的 MYSQL_USERNAME 默认值就是 runshi）
--    ⚠️ 执行前必改：把下面 IDENTIFIED BY 里的占位口令换成真实口令。
--      MySQL 的 CREATE USER 只接受字面量（用户变量在 DDL 里不生效，实测 1064），
--      所以口令就在这一行，没有中间层——改一处即可。
--    另外注意：改完别把带真实口令的文件提交回仓库，提交前改回占位符。
-- MySQL 8.0+ 支持 IF NOT EXISTS（项目要求 8.0+，见 DEPLOY.md 前置要求表）
CREATE USER IF NOT EXISTS 'runshi'@'%' IDENTIFIED BY 'CHANGE_ME_BEFORE_RUN';

-- ③ 最小权限授权：只要 CRUD，不给 DDL（DROP/ALTER/CREATE）——
--    应用账号被拖库时损失可控在数据层，结构不会被改。
--    项目没有任何存储过程 / 视图 / 触发器（纯 MyBatis-Plus），故不需要 EXECUTE。
GRANT SELECT, INSERT, UPDATE, DELETE ON `studio_db`.* TO 'runshi'@'%';
FLUSH PRIVILEGES;

-- ④ （本脚本到此为止）表结构**不在这里导入**，单独执行：
--      mysql -uroot -p < db/user.sql
--
--    为什么刻意不内嵌 SOURCE：user.sql 自带 `USE studio_db`（第 45 行），
--    嵌套执行时会把它前面的库切换覆盖掉——实测事故：DROP+CREATE 全部落回
--    studio_db，把开发库清空。两步各自单纯，胜过一步聪明。
--    user.sql 是 DROP+CREATE 结构脚本，只适合空库；本脚本则是幂等的环境准备，
--    两者用途不同、可重复性不同，混在一个入口里必然有一个的语义被破坏。
--
-- 完成后的收尾事项（本脚本管不到，见 DEPLOY.md 第 4 节）：
--   1. 前台注册一个账号，再 UPDATE sys_user SET user_role='admin' WHERE user_account='...';
--   2. 立即用强口令改密（后台「用户管理 → 重置密码」）；
--   3. 配置 ATTENDANCE_TRUSTED_PROXIES（Nginx 与应用不同机时填 Nginx 地址），
--      否则签到记录里保存的是 Nginx 的 IP（内外网判定已移除，不再需要考勤网段）。

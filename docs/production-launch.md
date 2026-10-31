# 生产上线部署与最终检查

> 目标环境：`deploy@192.168.6.130`（Ubuntu）
> 应用目录：`/opt/runshi`（`app.jar`、`dist/`、`app.env`）
> 服务：`runshi-studio.service`（systemd）
> Nginx：`/etc/nginx/conf.d/runshi-studio.conf`，静态根目录 `/opt/runshi/dist`
>
> 本文不记录任何账号、口令或密钥；生产密钥统一放在 `/opt/runshi/app.env`，仓库内只保留占位符。

---

## 1. 当前结论（先看这里）

| 检查项 | 结果 |
|---|---|
| 前端构建 | 通过 |
| 后端构建 | 通过，产物 `target/runshi-studio-web-0.0.1-SNAPSHOT.jar` |
| 后端测试 | **479 个全部通过** |
| 前端测试 | **88 个全部通过** |
| 前端已部署 | 已同步到 `/opt/runshi/dist`，站点返回 200 |
| 服务状态 | `runshi-studio` 为 active / enabled，Redis `PONG` |
| 必填环境变量 | `MYSQL_PASSWORD`、`REDIS_PASSWORD`、`AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL`、`AI_EMBEDDING_MODEL` 均已注入 |
| 测试 SQL | 本地的 `db/test-data-full.sql`、`db/quiz-test-data.sql` 已删除 |
| 生产库现状 | 22 个用户、15 篇文章；其中 **12 个是历史演示/测试账号** |
| 生产后端版本 | VM 上仍是 **2026-10-30 19:18 的旧 jar**；本地新 jar 为 2026-10-31 14:48 |

**上线前必须处理的两个阻塞项：**

1. **清理生产库中的 12 个历史演示/测试账号**（或逐个改密、禁用）。名单见 `DEPLOY.md` 4.1 节。
2. **把 VM 上的旧后端 jar 换成当前新构建的 jar**，并重启服务验证启动自检。

---

## 2. 本轮清理结果

### 已删除（测试/演示数据，禁止在生产导入）

- `db/test-data-full.sql`：会清空业务数据并灌入演示数据，文件头明确标注「不要在生产库执行」
- `db/quiz-test-data.sql`：刷题测试账号与测试作答数据

相关文档已同步更新：`README.md` 目录树、`DEPLOY.md` 口令轮换说明。

### 保留（生产需要的脚本）

| 文件 | 用途 |
|---|---|
| `db/user.sql` | 全量建库脚本，唯一权威 DDL 源头；**DROP + CREATE，只适合全新空库** |
| `db/init-prod.sql` | 建库、应用账号、最小权限授权；**不含表结构导入**，口令必须是占位符 |
| `db/audit-backfill.sql` | 审计列一次性回填；存量库按需在低峰执行 |
| `db/quiz-seed-history.sql` | 447 道真实题库种子，属于生产内容，不是测试账号数据；按需导入 |
| `db/backup.sh` | 数据库 + 上传目录备份脚本 |
| `db/seg/sysUser.sql` | 单表建表脚本，局部重建调试用 |

> 如果连 `quiz-seed-history.sql` 也不想保留，删除前请先确认线上题库已有独立备份。

---

## 3. 发布前检查清单

### 3.1 代码与构建

- [x] 前端 `npm run type-check` 通过
- [x] 前端 `npm run test:run`：8 个测试文件、88 个用例通过
- [x] 前端 `npm run build` 通过，`web/dist/default-avatar.png` 已包含
- [x] 后端 `.\mvnw.cmd test`：479 个用例通过
- [x] 后端 `.\mvnw.cmd clean package -DskipTests` 通过
- [x] 测试 SQL 已从本地删除
- [x] `db/init-prod.sql` 的 `CREATE USER` 口令已确认为占位符 `CHANGE_ME_BEFORE_RUN`
- [ ] 确认 `db/init-prod.sql` 工作区改动中没有误带真实口令（提交前再检查一次）
- [ ] 确认 `application-local.yml`、`.env` 等私有配置没有进 Git

### 3.2 生产环境

- [x] 服务 `runshi-studio` active / enabled
- [x] 首页 `http://127.0.0.1/` 返回 200
- [x] Redis 连通（PONG）
- [x] 6 个必填环境变量已注入
- [ ] **旧后端 jar 已替换为新构建版本**
- [ ] **历史演示/测试账号已清理或改密**
- [ ] HTTPS / 域名 / HSTS 已按正式访问方式确认
- [ ] 数据库与上传目录已完成一次上线前备份
- [ ] 备份 crontab 已挂载并实际验证过一次恢复

---

## 4. 数据库上线步骤

### 4.1 情况 A：全新空库

> 只在**没有业务数据**的新库上执行。

1. 编辑 `db/init-prod.sql`，把 `CHANGE_ME_BEFORE_RUN` 换成真实口令（改完不要提交）。
2. 执行环境准备：

   ```bash
   mysql -uroot -p < db/init-prod.sql
   ```

3. 导入表结构：

   ```bash
   mysql -uroot -p < db/user.sql
   ```

4. 可选：导入真实题库内容：

   ```bash
   mysql -uroot -p studio_db < db/quiz-seed-history.sql
   ```

5. 可选：存量审计列回填（新库一般不需要）：

   ```bash
   mysql -uroot -p studio_db < db/audit-backfill.sql
   ```

### 4.2 情况 B：已有数据的库（当前 VM 属于这种情况）

**不要执行 `db/user.sql` 或 `db/init-prod.sql`**：`user.sql` 会 DROP + CREATE，`init-prod.sql` 也面向空库。

上线前只做两件事：

1. **先备份**：

   ```bash
   DB_PASS='<应用口令>' bash db/backup.sh
   ```

2. **清理历史演示/测试账号**（先查、再改，执行前务必确认备份已完成）：

   ```sql
   -- 先确认数量与账号
   SELECT user_account, user_role, created_at
   FROM sys_user
   WHERE deleted_at = 0
     AND user_account IN (
       'shaoshing', 'chenyu', 'linxia', 'zhaoyang', 'wangmeng',
       'liuqing', 'sunhao', 'alumnus', 'tangke', 'zhouran',
       'xuyi', 'banned01', 'test_admin', 'test_member'
     );

   -- 推荐：先在后台「用户管理」逐个禁用/改密；
   -- 如果确认要逻辑删除，再执行：
   UPDATE sys_user
   SET deleted_at = UNIX_TIMESTAMP(NOW(3)) * 1000
   WHERE deleted_at = 0
     AND user_account IN (
       'shaoshing', 'chenyu', 'linxia', 'zhaoyang', 'wangmeng',
       'liuqing', 'sunhao', 'alumnus', 'tangke', 'zhouran',
       'xuyi', 'banned01', 'test_admin', 'test_member'
     );
   ```

> 这些账号可能与历史帖子、评论、成员档案有关联。优先在后台逐个处理；直接逻辑删除前先确认关联数据是否需要保留。

---

## 5. 后端发布步骤

> 当前 VM 上的 `app.jar` 仍是 2026-10-30 的旧版本，需要替换。

1. 本地构建（本次已完成）：

   ```powershell
   .\mvnw.cmd clean package -DskipTests
   ```

   产物：`target/runshi-studio-web-0.0.1-SNAPSHOT.jar`

2. 上传到 VM：

   ```bash
   scp target/runshi-studio-web-0.0.1-SNAPSHOT.jar deploy@192.168.6.130:/tmp/app.jar.new
   ```

3. 备份并替换：

   ```bash
   ssh deploy@192.168.6.130
   ts=$(date +%Y%m%d%H%M%S)
   cp /opt/runshi/app.jar /opt/runshi/app.jar.bak.$ts
   mv /tmp/app.jar.new /opt/runshi/app.jar
   sudo systemctl restart runshi-studio
   ```

4. 验证：

   ```bash
   sudo systemctl status runshi-studio --no-pager
   tail -n 80 /opt/runshi/app.log | grep -E '启动自检通过|Started|ERROR'
   curl -s -o /dev/null -w 'HTTP=%{http_code}\n' http://127.0.0.1/
   ```

   启动日志中出现「启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法」才算配置完整。

5. 回滚：

   ```bash
   sudo systemctl stop runshi-studio
   cp /opt/runshi/app.jar.bak.<时间戳> /opt/runshi/app.jar
   sudo systemctl start runshi-studio
   ```

---

## 6. 前端发布步骤

1. 构建（正式域名确定后带上 `VITE_SITE_URL`，否则分享预览图是相对路径）：

   ```powershell
   cd web
   $env:VITE_SITE_URL = 'https://你的域名'
   npm run build
   ```

2. 打包上传（在仓库根目录执行，Windows 自带 `tar` 也可用）：

   ```bash
   tar -czf runshi-dist.tgz -C web dist
   scp runshi-dist.tgz deploy@192.168.6.130:/tmp/runshi-dist.tgz
   ```

3. 备份并切换：

   ```bash
   ssh deploy@192.168.6.130
   ts=$(date +%Y%m%d%H%M%S)
   rm -rf /tmp/runshi-dist-new
   mkdir -p /tmp/runshi-dist-new
   tar -xzf /tmp/runshi-dist.tgz -C /tmp/runshi-dist-new --strip-components=1
   mv /opt/runshi/dist /opt/runshi/dist.old.$ts
   mv /tmp/runshi-dist-new /opt/runshi/dist
   ```

4. 验证：

   ```bash
   curl -s -o /dev/null -w 'PAGE=%{http_code}\n' http://127.0.0.1/
   curl -s -o /dev/null -w 'AVATAR=%{http_code}\n' http://127.0.0.1/default-avatar.png
   ```

   静态文件由 Nginx 直接托管，不需要 reload Nginx。

5. 回滚：

   ```bash
   mv /opt/runshi/dist /opt/runshi/dist.failed
   mv /opt/runshi/dist.old.<时间戳> /opt/runshi/dist
   ```

本次前端部署的备份示例：`/opt/runshi/dist.old.20261031141759`。

---

## 7. 上线冒烟测试

按顺序验证：

- [ ] `/`、`/projects`、`/certificates`、`/posts`、`/ai`、`/login`、`/register` 均可打开
- [ ] 登录 / 注册正常，注册后为普通用户，可进入 AI 助手
- [ ] 帖子列表与详情、评论、点赞正常
- [ ] 项目列表与详情、参与成员头像正常
- [ ] AI 助手能流式回答；普通用户 5 次配额提示正常
- [ ] 上传图片后 `/uploads/...` 可直接打开，页面不裂图
- [ ] 后台登录、用户管理、审核、荣誉与项目管理正常
- [ ] 首页数据条、荣誉图表、默认头像、备案号显示正常
- [ ] 手机端（≤760px）布局无明显溢出

---

## 8. 安全与运维注意事项

1. **测试 SQL 删除 ≠ Git 历史清除**：旧演示口令仍在 Git 历史中，相关账号必须改密或删除。
2. `db/init-prod.sql` 只能保留占位口令；真实口令通过 `app.env` 注入，不提交仓库。
3. `app.env` 权限建议 600，且只保存在 VM；不要复制进仓库或聊天记录。
4. MySQL / Redis 只监听 `127.0.0.1`；对公网只开放 Nginx 的 80/443。
5. 正式域名确定后：
   - 打开 Nginx HTTPS 段，配置证书与 80 → 443 跳转；
   - 确认 `X-Forwarded-Proto` / `X-Forwarded-Port` 已透传；
   - 构建前端时设置 `VITE_SITE_URL`；
   - 确认后再开启 HSTS。
6. 线上备份必须验证一次恢复，不能只看脚本执行成功。

---

## 9. 已知差异与备注

- `deploy/nginx.conf` 是通用样例，样例里的静态根目录是 `/opt/runshi-studio/web/dist`；当前 VM 实际使用 `/opt/runshi/dist`。以 VM 现有配置为准，修改前先备份。
- 本次 `PostSelfUpdateTest` 已同步到当前产品规则：作者可修改自己的待审 / 驳回 / 已通过帖子，保存后统一重置为待审；测试不再按旧规则断言。
- 前端 `web/.env.production` 中 `VITE_SITE_URL` 当前为空；局域网访问不影响，正式域名上线时再注入。
- 本次 VM 上运行的仍是旧后端 jar；前端已经是最新构建。两者发布顺序建议：先备份数据库 → 发布后端 → 发布前端 → 冒烟测试。

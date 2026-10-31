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

---

## 10. 2 核 2G 推荐版本与内存预算（详细）

### 10.1 当前 VM 实测

| 项目 | 实测值 |
|---|---|
| CPU | 2 核 |
| 内存 | 1963 MB |
| Swap | 2047 MB（已启用，几乎未使用） |
| 操作系统 | Ubuntu 22.04.5 LTS |
| JDK | OpenJDK 21.0.12.1 |
| MySQL | 8.0.46 |
| Redis | 7.2.5 |
| Nginx | 1.18.0 |
| Node.js | 未安装 |
| 当前内存 | used 约 1041 MB，available 约 762 MB |
| `VECTOR_STORE_TYPE` | `memory` |

> 结论：JDK 21 已达标；MySQL 8.0.46 可继续用；**Redis 7.2.5 不满足 RAG 向量库要求**，当前只能把向量放在 JVM 内存里。

### 10.2 推荐组合

| 组件 | 推荐版本 | 当前是否满足 | 建议 |
|---|---|---|---|
| 操作系统 | Ubuntu 22.04.5 LTS（继续用）或 24.04 LTS（重装时） | 是 | 现有 22.04 可用到 2027 年，不必为上线重装 |
| JDK | Eclipse Temurin 21 LTS，21.0.12 及以上 | 是 | 保持；不要换 17 / 25 |
| MySQL | 现有 8.0.46 保持；新装选 MySQL 8.4 LTS | 是 | 已有数据时不要临时跨大版本升级 |
| Redis | **8.4.x（使用 RAG Redis 向量库时）** | **否** | 7.2.5 只能跑 memory 向量库；要持久化向量必须升级 |
| Nginx | 1.26.x stable；现有 1.18.0 | 是（功能可用） | 1.18 是 22.04 自带版本；无安全要求可暂不升级 |
| Node.js | 22 LTS，只在本机构建 | 未安装（正确） | 2G 服务器上不要装 Node / Maven 构建 |
| Docker | 不建议 | 未使用 | 省内存，保持裸机 systemd 部署 |

### 10.3 为什么 Redis 是唯一必须升级的组件

- Redis 7.2.5 支持 Sa-Token 的 `KEEPTTL` 与配额回刷的 `GETDEL`，所以登录 / 限流 / AI 配额都能正常工作。
- 但 RAG 的 Redis 向量库要求 **8.4+ 的 Query Engine + JSON**；在 7.2.5 上把 `VECTOR_STORE_TYPE` 设为 `redis`，应用启动会直接失败。
- 当前 `VECTOR_STORE_TYPE=memory`，向量只存在 JVM 内存中：
  - 重启后向量全部丢失；
  - 启动自检会重新切块并调用 Embedding 模型重建向量，既慢又会产生额外调用费用。
- 升级 Redis 8.4 后建议把生产切到 `VECTOR_STORE_TYPE=redis`，并验证：

  ```bash
  redis-cli FT._LIST
  redis-cli FT.INFO studio-rag-chunks
  ```

  `FT.INFO` 里的 `num_docs` 应与 `studio_knowledge_chunk` 的存活行数一致。

### 10.4 2 核 2G 内存预算

| 进程 | 预估常驻内存 |
|---|---|
| 操作系统 + systemd + 基础服务 | 250–350 MB |
| Java（`-Xmx512m` + Metaspace / 线程 / 直接内存） | 700–850 MB |
| MySQL 8.0（buffer pool 256M） | 350–500 MB |
| Redis 8.4（含向量索引，`maxmemory 384M`） | 300–450 MB |
| Nginx | 20–40 MB |
| 合计 | **约 1.7–2.2 GB** |

**2G 是刚好够用的下限，不是舒适配置。** 当前 VM 已用约 1 GB，剩余可用约 760 MB；如果切到 Redis 向量库，Redis 内存会明显上升。建议：

1. **优先升到 2 核 4G**；这是最省事的方案。
2. 必须保持 2G 时，保留现有 2G swap，并严格限制 Redis、MySQL 和 JVM 的上限。
3. 前端和 Maven 构建都在本地完成，服务器只运行 jar + Nginx + MySQL + Redis。

### 10.5 2G 环境推荐参数

**Java（systemd）**

```ini
ExecStart=/usr/bin/java -Xms256m -Xmx512m \
  -XX:MaxMetaspaceSize=192m -XX:+UseG1GC \
  -jar /opt/runshi/app.jar
```

HikariCP 保持默认连接数（10），不要在 2 核 2G 上放大连接池。

**MySQL（`mysqld.cnf`）**

```ini
innodb_buffer_pool_size = 256M
innodb_redo_log_capacity = 256M
max_connections = 50
# 内存特别紧时可关闭；开启有利于排障
# performance_schema = OFF
```

**Redis（`redis.conf`）**

```ini
maxmemory 384mb
maxmemory-policy noeviction
appendonly yes
appendfsync everysec
```

- `noeviction` 是必须的：向量索引不能被 LRU 淘汰。
- 代价是内存满后写入会失败（登录、配额、向量写入都会受影响），必须监控 `used_memory` 与 `FT.INFO`，并预留余量。
- AOF 必须开启：AI 配额增量回刷依赖重启后不丢计数。

**Swap / 内核**

```bash
# 当前已有 2G swap，保留即可
vm.swappiness = 10
vm.overcommit_memory = 1
```

**Nginx**

```ini
worker_processes auto;   # 2 核，内存占用很小
```

### 10.6 升级优先级

1. **Redis 8.4.x**：如果生产需要 RAG 向量持久化，这是上线前唯一必须升级的组件。
2. **内存升到 4G**：如果预算允许，优先于其它软件升级。
3. MySQL 8.4 LTS：仅在重装 / 新建库时选择；现有 8.0.46 不要临时升级。
4. Nginx 1.26 stable：可选，升级前备份现有配置。
5. JDK 21：保持不变，跟随 21 LTS 的最新补丁即可。

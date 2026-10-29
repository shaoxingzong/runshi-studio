# 部署指南

> 阅读顺序：① 前置要求 → ② 构建 → ③ 环境变量 → ④ 初始化管理员 → ⑤ Nginx → ⑥ **上线检查清单**（第 6 节是上线前最后一道关，逐条过）。

---

## 1. 前置要求

| 组件 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | 后端构建与运行 |
| MySQL | 8.0+ | 库名 `studio_db`，字符集 utf8mb4 |
| Redis | **6.2+（登录/限流）；8.4+（RAG）** | Sa-Token 用 `KEEPTTL`（6.0+）、AI 配额回刷用 `GETDEL`（6.2+）；RAG 向量检索需要 8.4+ 的 Query Engine + JSON，缺少时 `VECTOR_STORE_TYPE=redis` 启动失败 |
| Nginx | 任意稳定版 | 反向代理 + 静态资源 + HTTPS 终止（配置样例见 `deploy/nginx.conf`） |
| Node.js | 20+ | 仅构建前端需要 |

---

## 2. 构建

### 2.1 后端

```bash
.\mvnw.cmd clean package -DskipTests
```

产物在 `target/runshi-studio-web-0.0.1-SNAPSHOT.jar`。

> **密钥不会进 jar**：`pom.xml` 里 maven-jar-plugin 排除了 `application-local.yml`，
> 本机调试用的 AI Key 与数据库口令不会随制品分发。生产密钥一律走第 3 节的环境变量。

### 2.2 前端

```bash
cd web
npm install
npm run build        # 产物在 web/dist/，交由 Nginx 托管
```

> `npm run build` 自带 `vue-tsc --noEmit` 类型检查，类型不过会直接构建失败——这是前端的质量门禁，别跳过。

### 2.3 建库（两步：环境准备 → 结构导入）

**第一步，环境准备**（建库、建应用用户 `runshi`、最小权限授权，幂等可重复执行）：

```bash
# ⚠️ 先编辑 db/init-prod.sql，把 CREATE USER 里的占位口令改成真实口令
mysql -uroot -p < db/init-prod.sql
```

**第二步，结构导入**（16 张表；**DROP+CREATE，只适合空库**，已在使用中的库不要整跑）：

```bash
mysql -uroot -p < db/user.sql
```

> ⚠️ 两步刻意分开，不合并成一个脚本：`user.sql` 自带 `USE studio_db`，
> 嵌套执行时会覆盖调用方的库切换——实测事故：DROP+CREATE 全部落回原库把数据清空。
> 两步各自单纯，胜过一步聪明。
>
> `runshi` 只授了 CRUD（无 DDL）：应用账号被拖库时损失可控在数据层，结构不会被改。

---

## 3. 环境变量清单

按 `application-prod.yml` 的约定分两组：密钥类不给默认值，路径类有默认值。

> ⚠️ **「缺失则启动失败」由 `StartupSelfCheck` 保证，不是配置语法保证**（2026-10-18 上线前实测发现）：
> 原以为「`${VAR}` 不带默认值 = 缺失即启动失败」，实测 Boot 4 有两条漏网路径——
> ① Hikari/Lettuce 懒建连，口令错误的连接失败**不冒泡到启动流程**，应用照常起、第一个请求才 `B0001`；
> ② `@ConfigurationProperties` 绑定路径把无法解析的占位符**原样传字面量**（`"${AI_API_KEY}"`），
> AiManager 的 isBlank 检查被绕过。两类问题的共同形态：**看「启动成功」以为配置齐全，实际带着坏配置上线**。
> 因此启动末尾有一道自检：`SELECT 1` + `PING` + AI 配置形态（字面量检测），任一不过即退出，
> 错误信息直接指出缺哪个变量。自检通过时日志有明确一行：
> `启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法`——**上线时看到这行才算真的配好了**。

### 3.1 必填（缺失则启动失败）

| 变量 | 说明 |
|---|---|
| `MYSQL_PASSWORD` | MySQL 口令 |
| `REDIS_PASSWORD` | Redis 口令 |
| `AI_BASE_URL` | AI 服务商地址（OpenAI 兼容，如 `https://dashscope.aliyuncs.com/compatible-mode/v1`） |
| `AI_API_KEY` | AI 服务商密钥 |
| `AI_MODEL` | 对话模型完整标识（如 `deepseek-v4-flash`） |
| `AI_EMBEDDING_MODEL` | 向量模型标识（如 `qwen3.7-text-embedding`） |

### 3.2 建议配置（有默认值，但生产应按实际情况改）

| 变量 | 默认值 | 什么时候必须改 |
|---|---|---|
| `MYSQL_URL` | `jdbc:mysql://127.0.0.1:3306/studio_db` | 数据库不在本机时 |
| `MYSQL_USERNAME` | `runshi` | 与数据库实际账号不一致时 |
| `FILE_ROOT_DIR` | `/data/runshi-studio-upload` | 上传目录想放别处时（改了记得同步 Nginx 的 `/uploads/` 代理目标） |
| `ATTENDANCE_LAN_CIDRS` | 空 | **只要用考勤功能就必须配**，否则全员签到记为「外网」（见第 5 节） |
| `ATTENDANCE_TRUSTED_PROXIES` | `127.0.0.1,::1` | Nginx 与应用**不同机**时必须填入 Nginx 的地址，否则考勤拿到的是 Nginx 的 IP |
| `WEB_ALLOWED_ORIGINS` | `https://你的域名`（占位符） | 前后端**不同域**部署时必须填真实域名；同域部署（Nginx 代理 `/api`）则无需跨域，保持占位即可 |
| `AUTH_RATE_LIMIT_ENABLED` | `true` | 一般不用动；排查误伤时可临时关闭 |
| `AI_GUEST_IP_LIMITS_ENABLED` | `true` | 同上 |
| `VECTOR_STORE_TYPE` | `redis`（prod） | 生产固定 `redis`；本地/测试可 `memory`（重启丢向量） |
| `RAG_STARTUP_CHECK_ENABLED` | `true` | 启动比对切分块数与向量数，不一致自动重建；测试环境已关闭 |

> **RAG 向量索引（Redis 8.4+）**：生产使用 `VECTOR_STORE_TYPE=redis` 前，先确认 Redis 含 Query Engine + JSON：
> `redis-cli FT._LIST` 不报错。首次全量同步后执行
> `redis-cli FT.INFO studio-rag-chunks`，其中 `num_docs` 应与 `studio_knowledge_chunk` 的存活行数一致。
> 向量索引会占用 Redis 内存，需纳入 `maxmemory` 规划；不要让它被 LRU 淘汰（建议独立实例/库或 `noeviction`）。
> `VECTOR_STORE_TYPE=memory` 仅用于本地开发，重启会丢失向量。

### 3.3 启动

```bash
java -jar runshi-studio-web-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

或用 systemd（示例，按需调整路径与用户）：

```ini
[Unit]
Description=runshi-studio-web
After=network.target mysql.service redis.service

[Service]
User=runshi
Environment=MYSQL_PASSWORD=xxx
Environment=REDIS_PASSWORD=xxx
Environment=AI_BASE_URL=xxx
Environment=AI_API_KEY=xxx
Environment=AI_MODEL=xxx
Environment=AI_EMBEDDING_MODEL=xxx
ExecStart=/usr/bin/java -jar /opt/runshi/runshi-studio-web-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

---

## 4. 初始化管理员（**建库脚本不再内置账号**）

历史教训：建库脚本曾内置 `shaoshing/123456`，而脚本上线必跑——等于上线即留下一个已知口令的管理员。现已删除，改为文档化流程：

1. **前台注册**一个普通账号（`/register`）；
2. 在库里把它提升为管理员：
   ```sql
   UPDATE sys_user SET user_role = 'admin' WHERE user_account = '你的账号' AND deleted_at = 0;
   ```
3. **立即修改密码**（后台「用户管理 → 重置密码」），使用强口令；
4. 用这个账号登录后台，后续再按需给其他成员授「成员」身份（用户管理里改角色即可）。

> ⚠️ 不要在 Git 仓库里提交任何带口令的 `INSERT`——BCrypt 哈希可复用不等于口令可公开。

### 4.1 口令轮换清单（公开仓库的历史包袱，上线必做）

本仓库早期的演示数据脚本（`db/test-data-full.sql`、`db/quiz-test-data.sql`）曾把口令写成注释。明文已从当前文件删除，但 **Git 历史仍可翻到**——删文件不等于止血，**改密才是唯一有效的处置**。

| 账号 | 来源 | 处置 |
|---|---|---|
| `shaoshing` | `db/test-data-full.sql`（角色 admin） | **立即改密或删除**；改密之前一律视为已被接管 |
| `chenyu` / `linxia` / `zhaoyang` / `wangmeng` / `liuqing` / `sunhao` / `alumnus` / `tangke` / `zhouran` / `xuyi` / `banned01` | 同上（共用同一条演示哈希） | 改密或删除；共用同一哈希＝同一把钥匙 |
| `test_admin` / `test_member` | `db/quiz-test-data.sql` | 测试账号，生产库不该存在；若已存在，直接删除或改密 |

改密的两条路径：

1. **推荐：后台改**。管理员登录 → 「用户管理」→ 找到账号 → 重置密码（BCrypt 由服务端生成，明文不出库）。
2. **手上只有数据库权限时**：前台注册一个账号 → 执行
   ```sql
   UPDATE sys_user SET user_role = 'admin' WHERE user_account = '你的账号' AND deleted_at = 0;
   ```
   → 登录后台，再按路径 1 重置其余账号。

> 仓库约束：任何 `db/*.sql`、文档、测试数据都不得出现口令明文，也不得写「默认密码是 xxx」。
> 哈希本身可以入库，但哈希对应的明文一旦公开，就必须当作口令已泄露——改密，不要只删注释。

---

## 5. 考勤内外网配置（`ATTENDANCE_LAN_CIDRS`）

判定逻辑：服务端解析客户端真实 IP，命中配置的任一工作室网段即记「内网」，否则记「外网」。**留空不报错，但所有签到都会记为外网**（安全侧默认：宁可记录不准，也不把未知地址当成内网），只有一条启动 warn——这类静默失效最难发现，所以上线 checklist 里有一条专门验它。

### 怎么取网段

在工作室任一联网电脑上执行 `ipconfig`，看 `IPv4 地址` 与 `子网掩码`：

```
IPv4 地址. . . . . . . . . . . . . : 192.168.1.23
子网掩码  . . . . . . . . . . . . . : 255.255.255.0
```

掩码 `255.255.255.0` 对应前缀 `/24`，网段写成 **网络地址**（主机位清零）：`192.168.1.0/24`。

多个网段用逗号分隔：`ATTENDANCE_LAN_CIDRS=192.168.1.0/24,10.0.5.0/24`。

### 配错的两种表现（对照排查）

| 现象 | 原因 |
|---|---|
| 全员记「外网」 | 网段没配 / 配错 / 掩码换算错 |
| 全员记「外网」且你确信网段没错 | Nginx 与应用不同机，且 `ATTENDANCE_TRUSTED_PROXIES` 没填 Nginx 地址——拿到的是 Nginx 自己的 IP |

> 安全边界说明：请求头（`X-Real-IP` 等）是客户端可伪造的，只有 TCP 对端在 `ATTENDANCE_TRUSTED_PROXIES` 里时才采信。默认值 `127.0.0.1,::1` 覆盖「Nginx 与应用同机」这一最常见形态。

---

## 6. Nginx

配置样例：[`deploy/nginx.conf`](deploy/nginx.conf)。它包含：前端静态资源托管（SPA fallback）、`/api` 与 `/uploads` 反向代理、gzip、安全响应头。

使用时的三个注意点：

1. **`/api` 代理必须传 `X-Real-IP` 与 `X-Forwarded-For`**（样例里已带），否则考勤与限流拿到的全是 Nginx 的 IP；
2. **`client_max_body_size` 与后端一致**（后端 multipart 上限 10MB，样例设为 `10m`）；
3. **HTPT/HSTS**：样例里 HSTS 默认注释掉，确认证书与跳转配置无误后再打开。

---

## 7. 数据备份（git 只备份代码，不备份数据）

帖子、成员、证书、流量、上传的图片**全是人工积累、丢了不可再生**的数据。用仓库里的脚本每天备份：

```bash
# db/backup.sh：MySQL 全量快照（单事务不锁表）+ 上传目录打包 + 保留 14 天
# 首次先手动跑一遍验证（把 DB_PASS 换成真实口令）：
DB_PASS='真实口令' bash db/backup.sh

# 验证产物（默认 /data/backup/runshi-studio/）：
ls -lh /data/backup/runshi-studio/
#   db_20261018_020000.sql.gz       数据库
#   uploads_20261018_020000.tar.gz  上传目录
```

**挂到 crontab**（每天凌晨 2 点，失败时发邮件——没有告警的备份等于没备份）：

```cron
0 2 * * * DB_PASS='真实口令' /usr/bin/bash /opt/runshi/db/backup.sh >> /data/logs/backup.log 2>&1
```

> 口令也可以放进 root 的 `~/.my.cnf`（权限 600）让脚本从 `DB_PASS` 环境变量退化为可选——
> 但**别把 .cnf 提交进仓库**。

**恢复步骤**（数据损坏 / 误删时）：

```bash
# ① 数据库（dump 内含 DROP TABLE IF EXISTS + CREATE，恢复到备份时完整状态）
gunzip < /data/backup/runshi-studio/db_YYYYmmdd_HHMMSS.sql.gz | mysql -uroot -p studio_db

# ② 上传目录（解到 /data 下，路径与 FILE_ROOT_DIR 对应）
tar -xzf /data/backup/runshi-studio/uploads_YYYYmmdd_HHMMSS.tar.gz -C /data
```

**为什么脚本里是这些参数**：`--single-transaction` 拿 InnoDB 一致性快照、不锁表不影响在线服务；`--no-tablespaces` 绕开应用用户没有的 PROCESS 权限（不加每次备份都报 Access denied，实测过）；保留 14 天与日志保留策略一致，运维只记一个数字。

---

## 8. 上线检查清单（上线前逐条过）

这是上线前安全评审沉淀出来的阻塞项，按「不解决会出事故」的程度排序：

- [ ] **HTTPS 已启用**（证书 + 跳转 + HSTS），数据库连接若跨机走内网或 TLS——明文传输下登录口令与 token 可被中间人截获
- [ ] **建库脚本未在生产库上整跑**（DROP+CREATE 会清空数据）；管理员已按第 4 节流程创建并改密
- [ ] **4.1 口令轮换清单已逐条处置**：`shaoshing` 与 11 个共用演示哈希的账号、`test_admin` / `test_member` 均已改密或删除
- [ ] **操作日志已生效**：后台「操作日志」页能看到刚才的审核 / 删除记录，且记录里有操作人账号与来源 IP（创建人列不再是 0）
- [ ] **环境变量 6 个必填项全部注入**；启动日志确认出现 `启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法`（见 3.1 节——缺变量时自检会让启动失败，而不是带着坏配置上线）
- [ ] `ATTENDANCE_LAN_CIDRS` 已配真实网段，且**启动日志确认** `考勤内外网判定已启用`（不是 warn）
- [ ] Nginx 与应用同机（或 `ATTENDANCE_TRUSTED_PROXIES` 已含 Nginx 地址）；签到一次验证「本次网络」显示内网
- [ ] `WEB_ALLOWED_ORIGINS` 已填真实域名（跨域部署时）；同域部署则确认前端 `.env.production` 的 `VITE_API_BASE_URL=/api`
- [ ] 上传目录 `FILE_ROOT_DIR` 已建且**应用用户可写**（目录本身首次上传时会自动创建，但父目录需要对应用用户可写，否则上传才暴露权限问题）；传一张图验证 `/uploads/` 可达
- [ ] 日志目录 `/data/logs/runshi-studio/` 已建；确认 `app.log` 按天滚动、`app.log.error` 单独归档（14 天 / 1GB 上限）
- [ ] 登录 / 注册限流生效：同一 IP 连续错误登录 11 次后返回「太频繁」（A0501）
- [ ] AI 功能冒烟：问一个问题，确认有回答、配额计数正常（普通用户第 6 次起被限）
- [ ] 备份策略：按第 7 节挂上 crontab，并**实际验证过一次恢复**（备份没验证过 = 没有备份）

---

## 9. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 启动即失败，提示某环境变量缺失 | 设计如此（密钥类无默认值）。按第 3.1 节补齐 |
| 登录接口 500 | Redis 低于 6.0 不支持 `KEEPTTL`；或 Redis 实际没设密码而配置里写了 |
| 全员签到记「外网」 | 见第 5 节两张排查表 |
| 前端页面 404 但接口正常 | Nginx 少了 SPA fallback（`try_files ... /index.html`），见样例配置 |
| 上传成功但图片裂图 | `/uploads/` 没代理到后端，或 `FILE_ROOT_DIR` 与应用实际写入目录不一致 |
| 日志把磁盘写满 | 不可能——`logback-spring.xml` 有按天+大小双滚动与 1GB 总容量上限；若看到异常增长，检查是否有高频 warn（如限流触发、AI 熔断） |

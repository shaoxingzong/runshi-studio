# 阿里云 2C2G 部署手册（Runshi Studio）

> 从全新 ECS 到浏览器能登录、能发帖。按顺序做，每步都有验证命令。
> 预计 1~2 小时（不含备案）。

---

## 0. 一页看懂

```
浏览器 → Nginx(80/443) → /opt/runshi/dist（前端）
                       → /api/ → 127.0.0.1:8080（Java，context-path=/api）
                       → /uploads/ → 127.0.0.1:8080/api/uploads/
                                        ├── MySQL 8
                                        └── Redis Stack（会话/限流/RAG 向量）
```

**2C2G 资源分配（关键）**：

| 组件 | 内存上限 | 说明 |
|---|---|---|
| Java | `-Xmx512m` | systemd 里固定 |
| MySQL | 256M（buffer pool） | 必须调小，默认 128M 起但连接/排序会吃更多 |
| Redis Stack | 256M（`maxmemory`） | 且必须 `noeviction`，否则向量会被淘汰 |
| 系统 + Nginx | 剩余 | 建议加 **2G swap** 兜底 |

> 2C2G 能跑，但**不要**在上面跑 Maven/Node 构建（构建时内存会飙）。建议本机构建后上传，或在 ECS 上临时加 swap 再构建。

### 你需要准备
- 阿里云账号、2C2G ECS（Ubuntu 22.04）
- 域名（建议；大陆需备案，不想备案选香港）
- 3 个强口令：MySQL 应用账号 / MySQL root / Redis
- 智谱 AI Key

---

## 1. 买 ECS + 安全组

| 项 | 选择 |
|---|---|
| 镜像 | Ubuntu 22.04 LTS 64 位 |
| 规格 | 2 核 2 G |
| 系统盘 | 40 G+ |
| 带宽 | 3~5 Mbps |

**安全组入方向**（只开这三个）：

| 端口 | 授权对象 |
|---|---|
| 22 | 你的公网 IP/32 |
| 80 | 0.0.0.0/0 |
| 443 | 0.0.0.0/0 |

> 3306 / 6379 一律不开（只监听 127.0.0.1）。

---

## 2. 2C2G 必做的系统调优

### 2.1 加 2G swap（防 OOM）

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf && sudo sysctl -p
free -h   # 应看到 Swap: 2.0Gi
```

### 2.2 MySQL 调小内存（装完 MySQL 后做）

```bash
sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf
# 在 [mysqld] 下加：
# innodb_buffer_pool_size = 256M
# performance_schema = OFF
# max_connections = 100

sudo systemctl restart mysql
```

### 2.3 Redis 限制内存（第 4 节启动时通过参数传入）

`--maxmemory 256mb --maxmemory-policy noeviction`（**不能是 allkeys-lru**，否则向量索引会被淘汰）。

---

## 3. 装环境

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git curl wget gpg apt-transport-https unzip nginx mysql-server

# JDK 21
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-21-jdk

# Node 20（只构建前端用）
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt install -y nodejs

# Docker（跑 Redis Stack）
sudo apt install -y docker.io && sudo systemctl enable --now docker
```

验证：`java -version`（21）、`node -v`（20）、`mysql --version`（8）、`nginx -v`。

---

## 4. 启动 Redis Stack

```bash
sudo docker run -d --name redis-stack --restart unless-stopped \
  -p 127.0.0.1:6379:6379 \
  -e REDIS_ARGS="--requirepass 你的Redis密码 --maxmemory 256mb --maxmemory-policy noeviction" \
  redis/redis-stack-server:latest
```

验证（**重点**：`FT._LIST` 不能报 unknown command）：

```bash
sudo docker exec -it redis-stack redis-cli -a '你的Redis密码' FT._LIST
sudo docker exec -it redis-stack redis-cli -a '你的Redis密码' INFO memory | grep maxmemory_human
```

---

## 5. 建库（两步）

```bash
cd /opt
sudo git clone https://github.com/shaoxingzong/runshi-studio.git
cd runshi-studio

# ① 改口令：把 CHANGE_ME_BEFORE_RUN 换成真实口令
sudo nano db/init-prod.sql

# ② 环境准备（幂等）
sudo mysql < db/init-prod.sql

# ③ 表结构（21 张；DROP+CREATE，只适合空库）
sudo mysql < db/user.sql
```

验证：

```bash
sudo mysql -e "USE studio_db; SHOW TABLES LIKE 'studio_operation_log';"
# 期望能查到
sudo mysql -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='studio_db';"
# 期望 21
```

---

## 6. 构建 + 放运行文件

> 2C2G 上构建可能吃紧。**推荐本机构建后上传** `target/*.jar` 和 `web/dist`；若在 ECS 构建，先确认 swap 已开。

```bash
cd /opt/runshi-studio
chmod +x mvnw
./mvnw clean package -DskipTests
cd web && npm ci && npm run build && cd ..

sudo mkdir -p /opt/runshi /data/runshi-studio-upload /data/logs/runshi-studio
sudo cp target/runshi-studio-web-0.0.1-SNAPSHOT.jar /opt/runshi/app.jar
sudo cp -r web/dist /opt/runshi/dist
sudo cp deploy/nginx.conf /etc/nginx/conf.d/runshi-studio.conf
```

---

## 7. 写 `/opt/runshi/app.env`

```bash
sudo nano /opt/runshi/app.env
```

```ini
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/studio_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=runshi
MYSQL_PASSWORD=你的MySQL应用口令

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=你的Redis口令

AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
AI_API_KEY=你的智谱Key
AI_MODEL=glm-4-flash
AI_EMBEDDING_MODEL=embedding-3

VECTOR_STORE_TYPE=redis
FILE_ROOT_DIR=/data/runshi-studio-upload
WEB_ALLOWED_ORIGINS=https://你的域名
ATTENDANCE_TRUSTED_PROXIES=127.0.0.1,::1
```

> 必填项缺一个，启动自检会直接退出并指出缺哪个。

---

## 8. systemd 托管（含 2C2G 的 JVM 参数）

`/etc/systemd/system/runshi-studio.service`：

```ini
[Unit]
Description=runshi-studio-web
After=network.target mysql.service docker.service

[Service]
User=root
EnvironmentFile=/opt/runshi/app.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m \
  -jar /opt/runshi/app.jar --spring.profiles.active=prod
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now runshi-studio
sudo journalctl -u runshi-studio -n 80 --no-pager | grep -E "启动自检通过|ERROR"
```

**必须看到**：`启动自检通过：数据库 / Redis 连通正常，AI 配置形态合法`  
再验证：`curl -s http://127.0.0.1:8080/api/actuator/health` → `"status":"UP"`。

---

## 9. Nginx

```bash
sudo nano /etc/nginx/conf.d/runshi-studio.conf
# 确认：root /opt/runshi/dist;  server_name 你的域名;
sudo nginx -t && sudo systemctl reload nginx
```

浏览器打开 `http://你的域名`（或公网 IP）。

---

## 10. 初始化管理员

1. 打开 `/register` 注册一个账号；
2. 提权：
```bash
sudo mysql -e "UPDATE studio_db.sys_user SET user_role='admin' WHERE user_account='你的账号';"
```
3. 登录后台，立刻在「用户管理 → 重置密码」改成强口令。

---

## 11. 上线验证清单

- [ ] health 返回 UP
- [ ] 首页、登录、发帖正常
- [ ] 上传图片后 `/uploads/...` 能直接打开（不裂图）
- [ ] 后台「操作日志」不报「系统错误」
- [ ] AI 问答有回答；后台「知识库 → 重建索引」后 sources 有召回
- [ ] 成员「打卡」能签到
- [ ] `journalctl` 无持续 ERROR
- [ ] 安全组只开 22/80/443

---

## 12. HTTPS（建议）

阿里云「数字证书管理服务」→ 免费证书 → 绑定域名 → 下载 **Nginx 格式** → 上传到 `/etc/nginx/cert/`，然后加 443 server 并做 80→443 跳转：

```nginx
server {
    listen 443 ssl;
    server_name 你的域名;
    ssl_certificate     /etc/nginx/cert/xxx.pem;
    ssl_certificate_key /etc/nginx/cert/xxx.key;
    # 其余 location 与 80 的 server 相同
}
server {
    listen 80;
    server_name 你的域名;
    return 301 https://$host$request_uri;
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
# 同时把 app.env 的 WEB_ALLOWED_ORIGINS 改成 https://你的域名 并重启后端
```

---

## 13. OSS 对象存储（重点）

### 13.1 先说清楚现状

**项目目前的图片上传是写本地磁盘的**（`FileManager` → `FILE_ROOT_DIR`，返回 `/uploads/yyyy/MM/xxx.png`），**没有 OSS 直传代码**。帖子正文的图片校验默认只允许 `/uploads/`，但已支持通过后端 `POST_ALLOWED_IMAGE_HOSTS` + 前端 `VITE_IMAGE_HOSTS` 放行指定的 OSS/CDN 域名。

所以 OSS 有两条路：

| 方案 | 要不要改代码 | 作用 | 建议 |
|---|---|---|---|
| **A. OSS 做备份** | ❌ 不用 | 数据库 dump、上传目录打包传到 OSS，防机器丢失 | **先做这个** |
| **B. 图片直传 OSS** | ✅ 要改 | 图片存 OSS + CDN，省磁盘、加速 | 以后再做 |

> 2C2G 的 40G 磁盘放图片能用很久，**先用方案 A** 把数据安全兜住，图片继续本地即可。

---

### 13.2 方案 A：OSS 做备份（现在就能做）

#### ① 创建 Bucket
OSS 控制台 → Bucket 列表 → 创建：
- **地域**：与 ECS **同地域**（走内网，免流量费）
- **读写权限**：**私有**（备份不需要公网读）
- **版本控制**：可选，开了更稳

#### ② 创建 RAM 子账号（不要用主账号 AccessKey）
RAM 访问控制 → 用户 → 创建用户 → 勾选「OpenAPI 调用访问」→ 保存 **AccessKey ID / Secret**。

授权（最小权限，只给它这个 Bucket 的读写）：
- 直接绑 `AliyunOSSFullAccess` 最省事（但权限偏大）；
- 更稳的是自定义策略：

```json
{
  "Version": "1",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["oss:PutObject", "oss:GetObject", "oss:ListObjects", "oss:AbortMultipartUpload"],
    "Resource": ["acs:oss:*:*:你的bucket", "acs:oss:*:*:你的bucket/*"]
  }]
}
```

#### ③ 安装 ossutil 并配置

```bash
wget https://gosspublic.alicdn.com/ossutil/1.7.19/ossutil64 -O ossutil
chmod +x ossutil && sudo mv ossutil /usr/local/bin/
ossutil config
# 依次填：
#   endpoint: oss-cn-你的地域-internal.aliyuncs.com   （ECS 同地域用内网）
#   accessKeyID / accessKeySecret
#   直接回车跳过 stsToken
```

验证：

```bash
ossutil ls oss://你的bucket/
ossutil cp /etc/hostname oss://你的bucket/test/hostname
ossutil ls oss://你的bucket/test/
```

#### ④ 把备份传到 OSS

```bash
# 先手动跑一次本地备份
DB_PASS='你的MySQL应用口令' bash /opt/runshi-studio/db/backup.sh

# 再整目录同步到 OSS（-u 只传新增/变化）
ossutil cp /data/backup/runshi-studio/ oss://你的bucket/backup/ -r -u
```

挂 crontab（每天 2:30，备份完自动传）：

```bash
sudo crontab -e
# 追加：
30 2 * * * DB_PASS='你的MySQL应用口令' /usr/bin/bash /opt/runshi-studio/db/backup.sh >> /data/logs/backup.log 2>&1 && /usr/local/bin/ossutil cp /data/backup/runshi-studio/ oss://你的bucket/backup/ -r -u >> /data/logs/backup.log 2>&1
```

#### ⑤ 生命周期（省钱）
Bucket → 生命周期 → 规则：
- 30 天后转**低频访问**
- 180 天后**删除**（或转归档）

#### ⑥ 验证恢复（必须做一次）
```bash
# 从 OSS 拉一个最新 dump 到临时目录，确认能解压
ossutil cp oss://你的bucket/backup/db_最新时间.sql.gz /tmp/
gunzip -t /tmp/db_最新时间.sql.gz && echo "备份可用"
```

---

### 13.3 方案 B：图片直传 OSS（需要改代码，先了解再决定）

如果以后想让图片走 OSS + CDN，需要做这些改动（**不是改个配置就行**）：

1. **加依赖**：`pom.xml` 引入 `aliyun-sdk-oss`；
2. **加配置**：`OSS_ENDPOINT / OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET / OSS_PUBLIC_HOST`；
3. **改 `FileManager.upload`**：文件写入 OSS，返回可访问 URL；
4. **处理清洗规则**：帖子正文的图片校验（后端 `PostServiceImpl`）与前端 `sanitizeMarkdownHtml(..., { restrictImages: true })` 默认只允许 `/uploads/`。两种解法：
   - **推荐**：上传代码改成返回 `/uploads/...` 相对路径，Nginx 把 `/uploads/` 反代到 OSS（前端与校验完全无感，无需配置白名单）；
   - **已支持**：直接返回 OSS/CDN 绝对地址时，在后端 `POST_ALLOWED_IMAGE_HOSTS` 与前端构建变量 `VITE_IMAGE_HOSTS` 里填同一个域名列表（逗号分隔），两处校验会放行这些域名的图片，其余外链仍会被拒绝。
     注意 `VITE_IMAGE_HOSTS` 是**构建时**变量，改完要重新 `npm run build`。
5. **Nginx**：`/uploads/` 不再反代到 8080，而是 `proxy_pass` 到 OSS 域名（或直接走 CDN）；
6. **测试**：上传、正文渲染、审核预览三处都要过一遍。

> 工作量约 0.5~1 天。**建议先上线 + 用方案 A 备份**，图片量真的上来了再切方案 B。

---

## 14. 常见问题

| 现象 | 处理 |
|---|---|
| 启动即退出，提示缺变量 | 补 `app.env` 的 6 个必填项 |
| `Redis 向量库不可用` | 没用 Redis Stack（缺模块），按第 4 节换镜像 |
| 2C2G 上构建被 OOM kill | 先开 swap，或改本机构建后上传 |
| 登录/发帖/上传 403 | Nginx 少了 `X-Forwarded-Proto/Port`，用仓库 `deploy/nginx.conf` |
| 图片裂图 | `/uploads/` 没代理到 `/api/uploads/`，或 `FILE_ROOT_DIR` 不一致 |
| 页面 404 接口正常 | Nginx 少了 SPA fallback |
| AI 回答「没有资料」 | 后台点「重建索引」；确认 Redis Stack 正常 |
| OSS 上传报 AccessDenied | RAM 策略缺 `oss:PutObject`，或 Bucket 地域/endpoint 不匹配 |

---

## 15. 与 `DEPLOY.md` 的差异

`DEPLOY.md` 第 5 节仍在写 `ATTENDANCE_LAN_CIDRS`，但**当前代码已移除该配置**：
- 不要配 `ATTENDANCE_LAN_CIDRS`；
- 只配 `ATTENDANCE_TRUSTED_PROXIES`（Nginx 同机默认 `127.0.0.1,::1` 即可）。

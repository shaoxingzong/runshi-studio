# 润石工作室官网 · 项目说明

高校工作室的一体化平台：**对外门户**（项目案例 / 荣誉墙 / 社区 / AI 助手）+ **成员工作台**（考勤签到 / 我的发帖 / 成员档案）+ **管理后台**（用户 / 成员 / 项目 / 证书 / 知识库 / 内容审核 / 流量分析）。

前后端**分离部署**：后端 Spring Boot（`/api` 前缀），前端 Vue3 独立工程（`web/`），生产由 Nginx 托管静态资源并把 `/api` 反向代理到后端。

---

## 功能模块

| 模块 | 能力 |
|---|---|
| 门户官网 | 项目案例（Markdown 正文）、荣誉墙、首页大盘（ECharts）、首页轮播 |
| 社区 | 发帖 / 评论（楼中楼）/ 点赞，**先审后发** + AI 三分法初判 |
| AI 助手 | RAG 知识库问答、SSE 流式输出、登录门禁 + 配额（普通用户限额）、熔断降级 |
| 成员工作台 | 考勤签到（内外网判定）、今日出勤看板、我的发帖与改稿 |
| 刷题 | 政治选择题（单选 / 多选）三种模式：顺序、错题重刷、随机；**服务端判分**、错题本自动收录与消解 |
| 站内通知 | 审核结论 / 评论回复 / 身份授予三类事件自动投递，未读红点 + 通知中心；幂等唯一键、不给自己发 |
| 管理后台 | 上述全部数据的增删改查、内容审核队列、知识库入库 / 重建、题库维护与批量导入、流量分析 |
| 基础设施 | 统一响应体与错误码、IP 双窗口限流、文件上传三重校验、日志滚动归档 |

---

## 技术栈

| 技术 | 版本 | 作用 |
|---|---|---|
| Spring Boot | 4.1.1 | 后端框架（`spring-boot-starter-webmvc`） |
| Java | 21 | 语言（虚拟线程、record、switch 表达式） |
| MyBatis-Plus | 3.5.17 | 数据库访问 |
| MySQL | 9.x | 数据库（20 张表，建库脚本 `db/user.sql`） |
| Redis | 8.x | 登录态（Sa-Token）、AI 配额计数、限流 |
| Sa-Token | 1.46.0 | 登录与角色鉴权 |
| LangChain4j | 1.20.2 | AI 咨询与 RAG（同步 + SSE 流式） |
| Vue 3 + TypeScript | 3.5 | 前端框架（`web/` 独立工程） |
| Element Plus | 2.9 | 组件库 |
| Vite | 7 | 前端构建与开发服务器 |

> Redis 硬性要求 **6.2+**：Sa-Token 会话用 `KEEPTTL`（6.0+），AI 配额回刷用 `GETDEL`（6.2+）。低于 6.0 的症状是「登录接口 500」。

---

## 快速开始

### 前置依赖

- JDK 21、Maven（或用自带的 `.\mvnw.cmd`）
- MySQL（库名 `studio_db`）与 Redis（设好 `requirepass`）
- Node.js 20+（跑前端）

### ① 建库

```bash
mysql -uroot -p123456 < db/user.sql
```

> ⚠️ 这是「先 DROP 再 CREATE」的全量脚本，**会清空数据**，只在空库上执行。

### ② 配本地私有配置（密钥不进 Git）

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
```

填入本机的 MySQL / Redis 口令与 AI 密钥（AI 可留空，应用照常启动，仅调用 AI 时返回「服务不可用」）。

### ③ 启动后端

```bash
.\mvnw.cmd spring-boot:run
```

接口前缀 `/api`，接口文档 `http://localhost:8080/api/doc.html`（dev profile 自动开启）。

### ④ 启动前端

```bash
cd web
npm install
npm run dev        # http://localhost:5173，/api 已代理到 8080
```

### ⑤ 跑测试

```bash
.\mvnw.cmd test    # 需要本机 MySQL + Redis 可达
```

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [HELP.md](HELP.md) | **开发约定**：分层职责、请求链路、注释规范、12 条全局约定、常见坑速查 |
| [DEPLOY.md](DEPLOY.md) | **部署指南**：环境变量清单、初始化管理员、内外网配置、上线检查清单 |
| [deploy/nginx.conf](deploy/nginx.conf) | Nginx 反向代理 + 静态资源 + 安全响应头配置样例 |
| [docs/erd.md](docs/erd.md) | 数据库 ER 图与关键设计取舍 |
| [docs/spring-mvc-request-flow.md](docs/spring-mvc-request-flow.md) | 一次请求从进来到响应的完整链路 |

---

## 目录结构

```
runshi-studio-web/
├── src/main/java/com/bhu/runshistudioweb/   后端源码（分层见 HELP.md 第 2 节）
├── src/main/resources/
│   ├── application.yml                       全局默认配置（安全默认值）
│   ├── application-dev.yml                   开发环境（文档开启、调试日志）
│   ├── application-prod.yml                  生产环境（密钥类无默认值，缺则启动失败）
│   ├── logback-spring.xml                    日志滚动（按天+大小、ERROR 单独归档）
│   └── application-local.yml.example         本地私有配置模板（复制后填真值，不进 Git）
├── src/test/java/                            自动化测试（单测 / MockMvc / DDL 校验）
├── web/                                      Vue3 前端独立工程
├── db/
│   ├── user.sql                              全量建库脚本（唯一权威 DDL 源头）
│   ├── init-prod.sql                         生产初始化（只建库表，不灌演示数据）
│   └── test-data-full.sql                    联调用的演示数据
├── docs/                                     ER 图与请求链路文档
└── deploy/nginx.conf                         生产部署配置样例
```

---

## 三条最重要的工程约定

改代码前务必读 [HELP.md](HELP.md) 第 5 节的完整清单，这里只说最容易被外部贡献者踩的三条：

1. **新增匿名接口必须登记白名单**，且只写精确路径——`/project/**` 会把管理端的 `/project/list/page` 一起放行；
2. **关联表实体不能加 `deletedAt`**——关联表是物理删除，加了会直接报 `Unknown column`；
3. **计数字段用 `Integer` 不用 `Long`**——全局 JSON 配置把 Long 序列化成字符串，前端会拿到 `"12"`。

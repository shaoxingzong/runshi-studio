#!/usr/bin/env bash
# ============================================================================================================
# 润石工作室 · 数据库与上传目录备份脚本
#
# 覆盖两类不可再生数据：
#   ① MySQL 全库（帖子 / 成员 / 证书 / 流量 / AI 会话——git 只备份代码，不备份这些）
#   ② 上传目录（帖子与项目正文里引用的站内图片，丢了就是裂图）
#
# 用法（crontab 示例见 DEPLOY.md 第 3 节）：
#   DB_PASS='真实口令' bash db/backup.sh
#
# 恢复步骤：
#   1. 解压：gunzip < backup/db_YYYYmmdd_HHMMSS.sql.gz | mysql -uroot -p studio_db
#      （全量 dump 内含 DROP TABLE IF EXISTS + CREATE，恢复到备份时的完整状态）
#   2. 上传目录：tar -xzf backup/uploads_YYYYmmdd_HHMMSS.tar.gz -C /data
#
# 设计取舍：
#   - --single-transaction：InnoDB 一致性快照，不锁表、不影响在线服务
#   - 保留 14 天：与 logback 的日志保留策略一致，运维心智只有一个数字
#   - 失败即非零退出（set -e）：crontab 里配 MAILTO 才能收到告警，静默失败等于没备份
# ============================================================================================================
set -euo pipefail

# ---- 配置（全部可用环境变量覆盖）----
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-studio_db}"
DB_USER="${DB_USER:-runshi}"
DB_PASS="${DB_PASS:?必须通过 DB_PASS 环境变量传入数据库口令}"
BACKUP_DIR="${BACKUP_DIR:-/data/backup/runshi-studio}"
KEEP_DAYS="${KEEP_DAYS:-14}"
UPLOAD_DIR="${UPLOAD_DIR:-/data/runshi-studio-upload}"
# mysqldump 可执行文件：默认在 PATH 里找；非标安装（如 Windows 手动实例）用环境变量指过来
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"

TS="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# ① 数据库全量快照（单事务 + 例程 + 触发器；utf8mb4 显式声明，避免恢复时字符集漂移）
#    口令走 MYSQL_PWD 而非 -p"$DB_PASS"：后者会把口令暴露在 ps 进程列表里
#    --no-tablespaces：mysqldump 默认要导出表空间信息，那需要 PROCESS 权限——
#    而应用用户按最小权限只给了 CRUD（见 db/init-prod.sql），不加这个flag
#    每次备份都会在 stderr 刷一条 Access denied（cron 邮件天天报警情）且部分版本直接中断
MYSQL_PWD="$DB_PASS" "$MYSQLDUMP_BIN" \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --single-transaction --no-tablespaces --routines --triggers \
    --default-character-set=utf8mb4 \
    "$DB_NAME" | gzip >"$BACKUP_DIR/db_$TS.sql.gz"

# ② 上传目录快照（目录不存在只 warn 不失败：上传功能可能尚未启用，
#    但数据库必须备份成功——两类数据的重要性不同，不能用同一个失败标准）
if [ -d "$UPLOAD_DIR" ]; then
    tar -czf "$BACKUP_DIR/uploads_$TS.tar.gz" \
        -C "$(dirname "$UPLOAD_DIR")" "$(basename "$UPLOAD_DIR")"
else
    echo "WARN: 上传目录 $UPLOAD_DIR 不存在，跳过上传快照（数据库备份已完成）"
fi

# ③ 保留策略：删掉超过 KEEP_DAYS 天的产物（按文件名时间戳而非 mtime——
#     文件被拷贝 / 恢复过 mtime 会变，时间戳才是备份事件的真实年龄）
find "$BACKUP_DIR" -maxdepth 1 -name 'db_*.sql.gz' -mtime "+$KEEP_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'uploads_*.tar.gz' -mtime "+$KEEP_DAYS" -delete

echo "备份完成：$BACKUP_DIR（保留 ${KEEP_DAYS} 天）"

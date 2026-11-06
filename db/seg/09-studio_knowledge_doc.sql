-- ============================================================================================================
-- 单表建表脚本：studio_knowledge_doc
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_knowledge_doc`;

CREATE TABLE `studio_knowledge_doc` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `title` varchar(128) NOT NULL COMMENT '文档标题（检索结果溯源展示用）',
    `source_type` varchar(16) NOT NULL COMMENT '来源类型：manual-手工录入, project-项目案例, member-成员档案, certificate-荣誉证书',
    `source_id` bigint DEFAULT NULL COMMENT '来源业务数据 ID（R2 溯源字段）：指向对应业务表主键；manual 来源为 NULL',
    `content_hash` varchar(64) NOT NULL COMMENT '正文内容哈希（增量更新去重：内容未变则跳过重建向量）',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '索引状态：0-待处理, 1-已索引, 2-失败',
    `chunk_count` int NOT NULL DEFAULT '0' COMMENT '已切分块数（与 studio_knowledge_chunk 的实际行数对齐）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    KEY `idx_source` (`source_type`, `source_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文档表（RAG 源文档元数据）';

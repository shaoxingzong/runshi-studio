-- ============================================================================================================
-- 单表建表脚本：studio_knowledge_chunk
-- 来源：db/init-all.sql（本文件由建表语句拆分生成，请勿单独手工维护）
-- ⚠️ 下方包含 DROP TABLE，执行会清空该表数据；改结构请改 db/init-all.sql 后重新拆分。
-- ============================================================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `studio_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `studio_db`;

DROP TABLE IF EXISTS `studio_knowledge_chunk`;

CREATE TABLE `studio_knowledge_chunk` (
    `id` bigint NOT NULL COMMENT '主键 ID（雪花算法 ASSIGN_ID 生成，非自增）',
    `doc_id` bigint NOT NULL COMMENT '所属文档 ID（关联 studio_knowledge_doc.id）',
    `chunk_index` int NOT NULL COMMENT '块序号（同一文档内从 0 开始递增，决定拼接顺序）',
    `content` text NOT NULL COMMENT '块正文（检索命中后拼上下文与溯源展示用）',
    `embedding_id` varchar(128) DEFAULT NULL COMMENT '向量库条目 ID（LangChain4j EmbeddingStore 的条目标识，用于按向量反查文本）',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    `updated_at` datetime NOT NULL COMMENT '更新时间',
    `created_by` bigint NOT NULL DEFAULT '0' COMMENT '创建人 ID',
    `updated_by` bigint NOT NULL DEFAULT '0' COMMENT '修改人 ID',
    `deleted_at` bigint NOT NULL DEFAULT '0' COMMENT '逻辑删除毫秒时间戳',
    PRIMARY KEY (`id`),
    -- 唯一键同样必须带 deleted_at：否则重建索引（删旧块+插新块）第二次会撞重复
    UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_index`, `deleted_at`),
    KEY `idx_embedding` (`embedding_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库切分块表';

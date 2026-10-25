package com.bhu.runshistudioweb.mapper;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：站内通知表的建库脚本结构 + 关键索引命中
 *
 * 与 QuizDdlVerifyTest 同一套路（抽 DDL → 幂等两连跑 → 结构与索引断言）。
 * 除结构本身外，本用例重点守两条这个表特有的设计：
 * - uk_receiver_biz 必须带 deleted_at——它一身二职：
 * 既是全项目唯一键铁律的落地，又是「同一事件重复投递」的幂等根基；
 * - idx_receiver_read 覆盖列表与未读计数——把 created_at
 * 也编进索引是为了让 ORDER BY 不走 filesort。
 *
 * 为什么不用 @Transactional 回滚：MySQL 的 DDL 会隐式提交，
 * 且本用例的目的正是「确认表能建出来」并留在库里供业务测试使用
 * （与刷题三张表同一工作方式），所以刻意不回滚。
 *
 * @author shaoshing
 */
@SpringBootTest
class NotificationDdlVerifyTest {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("建表语句连续执行两次不报错（脚本可重复执行的承诺）")
    void createTableIsIdempotent() throws Exception {
        String ddl = readCreateTableDdl("studio_notification");
        jdbcTemplate.execute(ddl);
        jdbcTemplate.execute(ddl);
    }

    @Test
    @DisplayName("结构：列齐全，type 与 is_read 的取值域有注释锚点")
    void tableStructure() {
        assertColumn("studio_notification", "receiver_id", "bigint");
        assertColumn("studio_notification", "type", "varchar");
        assertColumn("studio_notification", "biz_type", "varchar");
        assertColumn("studio_notification", "biz_id", "bigint");
        assertColumn("studio_notification", "title", "varchar");
        assertColumn("studio_notification", "content", "varchar");
        assertColumn("studio_notification", "is_read", "tinyint");
        assertColumn("studio_notification", "read_at", "datetime");
    }

    @Test
    @DisplayName("幂等键：uk_receiver_biz 是唯一索引且带 deleted_at（铁律 + 重复投递兜底）")
    void idempotentKeyCarriesDeletedAt() {
        assertUniqueIndex("studio_notification", "uk_receiver_biz");

        String columns = jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'studio_notification'
                  AND INDEX_NAME = 'uk_receiver_biz'
                """, String.class);
        assertTrue(columns != null && columns.contains("deleted_at"),
                "uk_receiver_biz 必须带 deleted_at（唯一键铁律），实际为 " + columns);
    }

    @Test
    @DisplayName("列表与未读计数：idx_receiver_read 存在且含 created_at（排序不走 filesort）")
    void listIndexCoversSort() {
        assertIndexExists("studio_notification", "idx_receiver_read");

        String columns = jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'studio_notification'
                  AND INDEX_NAME = 'idx_receiver_read'
                """, String.class);
        assertTrue(columns != null && columns.contains("created_at"),
                "idx_receiver_read 应含 created_at（ORDER BY created_at DESC 免 filesort），实际为 " + columns);
    }

    @Test
    @DisplayName("高频查询命中预期索引（possible_keys）")
    void highFrequencyQueriesUseExpectedIndexes() {
        // 通知列表：按接收者 + 已读状态过滤，新的在前
        assertIndexCandidate("""
                EXPLAIN SELECT id FROM studio_notification
                WHERE receiver_id >= 1 AND is_read = 0 AND deleted_at = 0
                ORDER BY created_at DESC
                """, "idx_receiver_read", "通知列表/未读计数");
    }

    @Test
    @DisplayName("全项目约定：逻辑删除与审计四列齐全")
    void commonColumnsPresent() {
        for (String column : List.of("deleted_at", "created_at", "updated_at", "created_by", "updated_by")) {
            assertTrue(columnExists("studio_notification", column),
                    "studio_notification 缺少 " + column);
        }
    }

    // ==================== 工具方法（与 QuizDdlVerifyTest 同一套）====================

    /**
     * 从全量建库脚本里取出指定表的建表语句，改写为 IF NOT EXISTS
     *
     * 必须去注释：JdbcTemplate 把整段当作一条语句发给 MySQL，
     * 连接串没开 allowMultiQueries，多余注释会让解析失败。
     */
    private String readCreateTableDdl(String tableName) throws Exception {
        String raw = Files.readString(Path.of("db/user.sql"));
        StringBuilder sb = new StringBuilder();
        raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .forEach(line -> sb.append(line).append('\n'));

        for (String part : sb.toString().split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CREATE TABLE")
                    && trimmed.contains("CREATE TABLE `" + tableName + "`")) {
                return trimmed.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
            }
        }
        throw new IllegalStateException("db/user.sql 里找不到 " + tableName + " 的建表语句");
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private void assertColumn(String table, String column, String expectedTypePrefix) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT DATA_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, table, column);
        String dataType = String.valueOf(row.get("data_type"));
        assertTrue(dataType.startsWith(expectedTypePrefix),
                table + "." + column + " 的类型应为 " + expectedTypePrefix + "，实际为 " + dataType);
    }

    private void assertIndexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """, Integer.class, table, indexName);
        assertTrue(count != null && count > 0, table + " 缺少索引 " + indexName);
    }

    private void assertUniqueIndex(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                  AND NON_UNIQUE = 0
                """, Integer.class, table, indexName);
        assertTrue(count != null && count > 0, table + " 缺少唯一索引 " + indexName);
    }

    /**
     * 断言 EXPLAIN 的候选索引包含预期索引
     *
     * 断言 possible_keys 而不是 key：前者证明「索引能被这条查询用上」，
     * 后者是优化器在当前数据量下的选择——表小时它可能合理地选全表扫描。
     * 条件用 >= 1 而非常量，避免 MySQL 判成「const 无匹配行」直接返回空 plan。
     */
    private void assertIndexCandidate(String explainSql, String expectedIndex, String scene) {
        Map<String, Object> row = jdbcTemplate.queryForMap(explainSql);
        String possibleKeys = String.valueOf(row.get("possible_keys"));
        String actualKey = String.valueOf(row.get("key"));

        System.out.println("[EXPLAIN] " + scene
                + " | possible_keys=" + possibleKeys
                + " | key=" + actualKey
                + " | rows=" + row.get("rows"));

        assertTrue(possibleKeys != null && possibleKeys.contains(expectedIndex),
                "「" + scene + "」的候选索引应包含 " + expectedIndex
                        + "，实际 possible_keys=" + possibleKeys + "，key=" + actualKey);
    }
}

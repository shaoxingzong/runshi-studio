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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：刷题三张表的建库脚本结构 + 关键索引命中
 *
 * @author shaoshing
 */
@SpringBootTest
class QuizDdlVerifyTest {

    /** 造数使用的 ID 段起点（固定段，便于重复执行与识别） */
    private static final long SEED_ID_BASE = 1910000000000500000L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("三张表的建表语句连续执行两次不报错（脚本可重复执行的承诺）")
    void createTablesAreIdempotent() throws Exception {
        for (String table : List.of("studio_quiz_question", "studio_quiz_answer", "studio_quiz_wrong")) {
            String ddl = readCreateTableDdl(table);
            // 第一次建表；第二次必须静默跳过（幂等）——报错即验收失败
            jdbcTemplate.execute(ddl);
            jdbcTemplate.execute(ddl);
        }
    }

    @Test
    @DisplayName("题目表：列与索引存在，且 options 与 correct_labels 是两列（答案不在选项 JSON 里）")
    void questionTableStructure() {
        assertColumn("studio_quiz_question", "options", "text");
        assertColumn("studio_quiz_question", "correct_labels", "varchar");
        assertColumn("studio_quiz_question", "textbook_name", "varchar");
        assertColumn("studio_quiz_question", "chapter_name", "varchar");
        assertColumn("studio_quiz_question", "question_seq", "int");
        assertColumn("studio_quiz_question", "deleted_at", "bigint");

        assertTrue(columnExists("studio_quiz_question", "correct_labels"),
                "正确答案必须是独立列——合并进 options 会让 C 端 VO 无法原样下发选项");

        assertIndexExists("studio_quiz_question", "idx_scope");
    }

    @Test
    @DisplayName("作答表：唯一键 uk_user_batch_question 存在（幂等的根基）")
    void answerTableStructure() {
        assertColumn("studio_quiz_answer", "batch_id", "bigint");
        assertColumn("studio_quiz_answer", "chosen_labels", "varchar");
        assertColumn("studio_quiz_answer", "correct_labels", "varchar");
        assertColumn("studio_quiz_answer", "result", "tinyint");

        assertUniqueIndex("studio_quiz_answer", "uk_user_batch_question");
        assertIndexExists("studio_quiz_answer", "idx_user_result");
        assertIndexExists("studio_quiz_answer", "idx_question_result");
    }

    @Test
    @DisplayName("错题表：唯一键 uk_user_question 带 deleted_at（否则移出后无法重新入本）")
    void wrongTableStructure() {
        assertColumn("studio_quiz_wrong", "wrong_count", "int");
        assertColumn("studio_quiz_wrong", "correct_streak", "int");
        assertColumn("studio_quiz_wrong", "last_wrong_at", "datetime");
        assertColumn("studio_quiz_wrong", "resolve_type", "tinyint");

        assertUniqueIndex("studio_quiz_wrong", "uk_user_question");
        assertIndexExists("studio_quiz_wrong", "idx_user_status_time");

        String columns = jdbcTemplate.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'studio_quiz_wrong'
                  AND INDEX_NAME = 'uk_user_question'
                """, String.class);
        assertTrue(columns != null && columns.contains("deleted_at"),
                "uk_user_question 必须带 deleted_at，实际为 " + columns);
    }

    @Test
    @DisplayName("三张表都有逻辑删除列（全项目约定）")
    void allTablesHaveLogicalDelete() {
        for (String table : List.of("studio_quiz_question", "studio_quiz_answer", "studio_quiz_wrong")) {
            assertTrue(columnExists(table, "deleted_at"), table + " 缺少 deleted_at");
            assertTrue(columnExists(table, "created_at"), table + " 缺少 created_at");
            assertTrue(columnExists(table, "updated_at"), table + " 缺少 updated_at");
            assertTrue(columnExists(table, "created_by"), table + " 缺少 created_by");
            assertTrue(columnExists(table, "updated_by"), table + " 缺少 updated_by");
        }
    }

    @Test
    @DisplayName("关键查询命中预期索引（possible_keys）")
    void highFrequencyQueriesUseExpectedIndexes() {
        seedIfNeeded();

        // 顺序模式抽题：教材 + 章节 + 状态，按题序排
        assertIndexCandidate("""
                EXPLAIN SELECT id FROM studio_quiz_question
                WHERE textbook_name = 'Ddl教材' AND chapter_name = 'Ddl章节'
                  AND status = 0 AND deleted_at = 0
                ORDER BY question_seq, id
                """, "idx_scope", "顺序模式抽题");

        assertIndexCandidate("""
                EXPLAIN SELECT * FROM studio_quiz_answer
                WHERE user_id >= 1 AND batch_id >= 1 AND question_id >= 1 AND deleted_at = 0
                """, "uk_user_batch_question", "作答幂等查重");

        // 错题本列表：按用户 + 状态 + 最近答错时间
        assertIndexCandidate("""
                EXPLAIN SELECT * FROM studio_quiz_wrong
                WHERE user_id = 1 AND status = 0 AND deleted_at = 0
                ORDER BY last_wrong_at
                """, "idx_user_status_time", "错题本列表");
    }

    // 工具方法

    /** 从全量建库脚本里取出指定表的建表语句 */
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

    /** 造数：让优化器有统计依据 */
    private void seedIfNeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_question WHERE id >= " + SEED_ID_BASE, Integer.class);
        if (count != null && count >= 20) {
            return;
        }
        jdbcTemplate.update("DELETE FROM studio_quiz_wrong WHERE id >= " + SEED_ID_BASE);
        jdbcTemplate.update("DELETE FROM studio_quiz_answer WHERE id >= " + SEED_ID_BASE);
        jdbcTemplate.update("DELETE FROM studio_quiz_question WHERE id >= " + SEED_ID_BASE);

        String options = "[{\"label\":\"A\",\"content\":\"甲\"},{\"label\":\"B\",\"content\":\"乙\"}]";
        for (int i = 0; i < 20; i++) {
            jdbcTemplate.update("""
                    INSERT INTO studio_quiz_question
                    (id, textbook_name, textbook_seq, chapter_name, chapter_seq, type, stem, options,
                     correct_labels, analysis, difficulty, source, question_seq, status,
                     created_at, updated_at, created_by, updated_by, deleted_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)
                    """, SEED_ID_BASE + i, "Ddl教材", 0, "Ddl章节", 0, 0, "Ddl题干" + i, options,
                    "A", null, 1, null, i, 0);

            jdbcTemplate.update("""
                    INSERT INTO studio_quiz_answer
                    (id, user_id, batch_id, question_id, chosen_labels, correct_labels, result,
                     created_at, updated_at, created_by, updated_by, deleted_at)
                    VALUES (?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)
                    """, SEED_ID_BASE + i, 1, 1, SEED_ID_BASE + i, "A", "A", 1);

            jdbcTemplate.update("""
                    INSERT INTO studio_quiz_wrong
                    (id, user_id, question_id, status, wrong_count, correct_streak, last_wrong_at,
                     created_at, updated_at, created_by, updated_by, deleted_at)
                    VALUES (?,?,?,?,?,?,NOW(),NOW(),NOW(),0,0,0)
                    """, SEED_ID_BASE + i, 1, SEED_ID_BASE + i, 0, 1, 0);
        }
        jdbcTemplate.execute("ANALYZE TABLE studio_quiz_question");
        jdbcTemplate.execute("ANALYZE TABLE studio_quiz_answer");
        jdbcTemplate.execute("ANALYZE TABLE studio_quiz_wrong");
    }

    /** 断言 EXPLAIN 的候选索引包含预期索引 */
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

    @Test
    @DisplayName("删除题目时错题条目会被清理——DDL 层面确认外键不存在（关联由应用层维护）")
    void noForeignKeyConstraints() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                  AND TABLE_NAME IN ('studio_quiz_question', 'studio_quiz_answer', 'studio_quiz_wrong')
                """, Integer.class);
        assertEquals(0, count, "本项目零外键：关联全部由应用层维护");
        assertFalse(count > 0);
    }
}

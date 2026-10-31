package com.bhu.runshistudioweb.mapper;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：操作日志表的建库脚本结构 + 四条高频查询命中预期索引
 *
 * @author shaoshing
 */
@SpringBootTest
class OperationLogDdlVerifyTest {

    /** 造数使用的 ID 段起点（与其它 DDL 验收用例错开，便于识别与清理） */
    private static final long SEED_ID_BASE = 1900000000000700000L;

    /** 造数规模：让优化器有统计依据，又不至于拖慢用例 */
    private static final int SEED_COUNT = 12;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("建表语句连续执行两次不报错，四条查询的候选索引符合预期")
    void migrationIsIdempotentAndIndexesMatch() throws Exception {
        List<String> statements = readCreateTableStatements("studio_operation_log");
        assertEquals(1, statements.size(), "建库脚本里应恰好有一条 studio_operation_log 的建表语句");

        for (String sql : statements) {
            // 第一次建表；第二次必须静默跳过（幂等）——报错即验收失败
            jdbcTemplate.execute(sql);
            jdbcTemplate.execute(sql);
        }

        seedIfNeeded();

        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE created_at >= '2020-01-01 00:00:00' AND deleted_at = 0 "
                        + "ORDER BY created_at DESC, id DESC LIMIT 10",
                "idx_created", "日志默认视图（时间倒序翻页）");

        // 「这个管理员最近都干了什么」
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE operator_id = 1 AND deleted_at = 0 ORDER BY created_at DESC LIMIT 10",
                "idx_operator_created", "按操作人反查");

        // 「这类动作最近发生过哪些」
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE action = 'POST_REJECT' AND deleted_at = 0 ORDER BY created_at DESC LIMIT 10",
                "idx_action_created", "按动作反查");

        // 「这条内容被谁处置过」——事故排查的主路径
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE target_type = 'POST' AND target_id = " + SEED_ID_BASE + " AND deleted_at = 0",
                "idx_target", "按内容反查处置记录");

        jdbcTemplate.update("DELETE FROM studio_operation_log WHERE id = " + (SEED_ID_BASE + 900L));
        String longDetail = "细".repeat(1000);
        jdbcTemplate.update(
                "INSERT INTO studio_operation_log (id, operator_id, operator_account, operator_name, action,"
                        + " target_type, target_id, target_title, detail, ip, created_at, updated_at,"
                        + " created_by, updated_by, deleted_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                SEED_ID_BASE + 900L, 1L, "ddl_verify", "验收", "POST_REJECT",
                "POST", SEED_ID_BASE, "验收造数帖子", longDetail, "127.0.0.1");
    }

    /** 从全量建库脚本里取出目标表的建表语句 */
    private List<String> readCreateTableStatements(String... tableNames) throws Exception {
        String raw = Files.readString(Path.of("db/user.sql"));
        StringBuilder builder = new StringBuilder();
        raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .forEach(line -> builder.append(line).append('\n'));

        List<String> statements = new ArrayList<>();
        for (String part : builder.toString().split(";")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith("CREATE TABLE")) {
                continue;
            }
            for (String table : tableNames) {
                if (trimmed.contains("CREATE TABLE `" + table + "`")) {
                    statements.add(trimmed.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
                    break;
                }
            }
        }
        return statements;
    }

    /** 造数：让优化器有统计依据，同时验证 NOT NULL 列都有默认值或显式赋值 */
    private void seedIfNeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_operation_log WHERE id >= " + SEED_ID_BASE
                        + " AND id < " + (SEED_ID_BASE + 900L), Integer.class);
        if (count != null && count >= SEED_COUNT) {
            return;
        }
        jdbcTemplate.update("DELETE FROM studio_operation_log WHERE id >= " + SEED_ID_BASE
                + " AND id < " + (SEED_ID_BASE + 900L));

        String[] targetTypes = {"POST", "COMMENT", "USER", "QUIZ"};
        for (int i = 0; i < SEED_COUNT; i++) {
            jdbcTemplate.update(
                    "INSERT INTO studio_operation_log (id, operator_id, operator_account, operator_name, action,"
                            + " target_type, target_id, target_title, detail, ip, created_at, updated_at,"
                            + " created_by, updated_by, deleted_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                    SEED_ID_BASE + i, 1L, "ddl_verify", "验收账号",
                    "POST_REJECT", targetTypes[i % targetTypes.length], SEED_ID_BASE + i,
                    "验收造数对象 " + i, "验收造数细节 " + i, "127.0.0.1");
        }

        jdbcTemplate.execute("ANALYZE TABLE studio_operation_log");
    }

    /** 断言候选索引包含目标索引，并打印实际执行计划便于排查 */
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

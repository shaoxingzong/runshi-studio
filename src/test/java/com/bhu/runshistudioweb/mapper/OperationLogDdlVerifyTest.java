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
 * 与 PostDdlVerifyTest 同一套工作方式：从 db/user.sql 抽出建表语句、改写成
 * CREATE TABLE IF NOT EXISTS 后连跑两次（第二次必须静默跳过），再实际插入几行验证列宽与默认值。
 *
 * 为什么要测索引：日志表只增不减，后台默认视图是「按时间倒序翻页」，
 * 事故排查又常常按「操作人 / 动作 / 对象」反查。少一条索引，二者之一就会退化成全表扫描——
 * 这种退化在上线初期完全看不出来，等表大了才发现，代价是整页超时。
 *
 * 不用 @Transactional 回滚：本用例要验证的正是「建表语句能被真实执行」，
 * 造数控制在固定 ID 段内，重复跑不会膨胀。
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

        // ① 默认视图：按时间倒序翻页。created_at 进 WHERE 是为了让优化器有前缀可用，
        //    ORDER BY 的 id 是同一秒内的稳定次序（批量审核一次就是多行）
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE created_at >= '2020-01-01 00:00:00' AND deleted_at = 0 "
                        + "ORDER BY created_at DESC, id DESC LIMIT 10",
                "idx_created", "日志默认视图（时间倒序翻页）");

        // ② 「这个管理员最近都干了什么」
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE operator_id = 1 AND deleted_at = 0 ORDER BY created_at DESC LIMIT 10",
                "idx_operator_created", "按操作人反查");

        // ③ 「这类动作最近发生过哪些」
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE action = 'POST_REJECT' AND deleted_at = 0 ORDER BY created_at DESC LIMIT 10",
                "idx_action_created", "按动作反查");

        // ④ 「这条内容被谁处置过」——事故排查的主路径
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_operation_log "
                        + "WHERE target_type = 'POST' AND target_id = " + SEED_ID_BASE + " AND deleted_at = 0",
                "idx_target", "按内容反查处置记录");

        // ⑤ 列宽兜底：detail 写满 1000 字符不报错（Service 的截断逻辑以此为依据）
        //    先删再插：本用例不用事务回滚，重复执行时不能撞主键
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

    /**
     * 从全量建库脚本里取出目标表的建表语句
     *
     * 与 PostDdlVerifyTest 保持一致的两处处理：去掉注释行（避免按分号误切），
     * 并把 CREATE TABLE 改写成 IF NOT EXISTS（本用例不想 DROP 掉开发库数据）。
     *
     * @param tableNames 目标表名
     * @return 建表语句列表
     */
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

    /**
     * 造数：让优化器有统计依据，同时验证 NOT NULL 列都有默认值或显式赋值
     *
     * 用固定 ID 段先删后插，重复执行不膨胀；四种对象类型都覆盖，
     * 便于 ④ 的按对象类型查询有可选择度。
     */
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

        // 刷新统计信息，否则优化器可能用上一次的旧统计决定执行计划
        jdbcTemplate.execute("ANALYZE TABLE studio_operation_log");
    }

    /**
     * 断言候选索引包含目标索引，并打印实际执行计划便于排查
     *
     * 断言 possible_keys 而不是 key：后者受统计信息与数据分布影响，
     * 在测试库里会随数据量波动；候选索引是否包含目标索引才是「索引定义对不对」的稳定信号。
     *
     * @param explainSql EXPLAIN 语句
     * @param expectedIndex 期望出现的索引名
     * @param scene 场景描述（失败时能立刻定位）
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

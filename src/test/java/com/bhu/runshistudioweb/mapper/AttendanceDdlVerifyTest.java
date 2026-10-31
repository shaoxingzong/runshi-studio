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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：考勤表建库脚本结构 + 三条高频 SQL 命中预期索引
 *
 * @author shaoshing
 */
@SpringBootTest
class AttendanceDdlVerifyTest {

    /** 造数使用的 ID 段起点（固定段，便于重复执行与识别） */
    private static final long SEED_ID_BASE = 1900000000000400000L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("建表语句连续执行两次不报错，且三条高频 SQL 的候选索引符合预期")
    void migrationIsIdempotentAndIndexesMatch() throws Exception {
        String ddl = readCreateTableDdl();

        // 第一次建表；第二次必须静默跳过（幂等）——报错即验收失败
        jdbcTemplate.execute(ddl);
        jdbcTemplate.execute(ddl);

        seedIfNeeded();

        // C 端「我的记录」：等值 user_id + 时间倒序分页
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_attendance "
                        + "WHERE user_id = 1 AND deleted_at = 0 ORDER BY check_in_at DESC LIMIT 10",
                "idx_user_time", "我的记录（按用户 + 时间倒序）");

        // admin 按天 + 内外网筛选
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_attendance "
                        + "WHERE attendance_date = '2026-10-04' AND in_lan = 1 AND deleted_at = 0 "
                        + "ORDER BY check_in_at DESC LIMIT 10",
                "idx_date_lan_time", "按天 + 内外网筛选");

        // admin 日报表：按天分组统计
        assertIndexCandidate(
                "EXPLAIN SELECT in_lan, COUNT(*) FROM studio_attendance "
                        + "WHERE attendance_date = '2026-10-04' AND deleted_at = 0 GROUP BY in_lan",
                "idx_date_lan_time", "按天分组统计（日报表）");
    }

    /** 从全量建库脚本里取出考勤表的建表语句 */
    private String readCreateTableDdl() throws Exception {
        String raw = Files.readString(Path.of("db/user.sql"));
        StringBuilder sb = new StringBuilder();
        raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .forEach(line -> sb.append(line).append('\n'));

        for (String part : sb.toString().split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CREATE TABLE")
                    && trimmed.contains("CREATE TABLE `studio_attendance`")) {
                return trimmed.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ");
            }
        }
        throw new IllegalStateException("db/user.sql 里找不到 studio_attendance 的建表语句");
    }

    /** 造数：让优化器有统计依据 */
    private void seedIfNeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_attendance WHERE id >= " + SEED_ID_BASE, Integer.class);
        if (count != null && count >= 60) {
            return;
        }
        jdbcTemplate.update("DELETE FROM studio_attendance WHERE id >= " + SEED_ID_BASE);

        String[] dates = {"2026-10-02", "2026-10-03", "2026-10-04"};
        int i = 0;
        for (int u = 1; u <= 10; u++) {
            for (String date : dates) {
                for (int lan = 0; lan <= 1; lan++) {
                    jdbcTemplate.update(
                            "INSERT INTO studio_attendance (id, user_id, attendance_date, check_in_at, in_lan, ip,"
                                    + " user_agent, created_at, updated_at, created_by, updated_by, deleted_at)"
                                    + " VALUES (?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                            SEED_ID_BASE + i, u, date, date + " 09:00:00", lan,
                            "192.168.1." + (10 + u), "ddl-verify-seed");
                    i++;
                }
            }
        }
        jdbcTemplate.execute("ANALYZE TABLE studio_attendance");
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
}

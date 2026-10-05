package com.bhu.runshistudioweb.mapper;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收：考勤表迁移脚本幂等 + 三条高频 SQL 命中预期索引
 *
 * author: shaoshing
 *
 * <p>验证三件事（对应 验收条款）：
 * <ol>
 *     <li><b>增量脚本连续执行两次不报错</b>：脚本用 {@code CREATE TABLE IF NOT EXISTS}，
 *     索引写在建表语句内部，因此第二次执行整句被跳过。注意 MySQL 没有
 *     {@code CREATE INDEX IF NOT EXISTS}，所以「索引写在建表语句内」是幂等的前提；</li>
 *     <li><b>三条高频 SQL 命中预期索引</b>：见 {@code DESIGN.md} 场景 O。
 *     断言的是 {@code possible_keys}——它证明「索引对该查询可用且匹配」；
 *     实际是否选用（{@code key} 列）由优化器基于统计信息决定，
 *     表很小时优化器可能选全表扫描，那不是设计问题；</li>
 *     <li><b>表结构就位</b>：后续 的实体与 Mapper 依赖这张表。</li>
 * </ol>
 *
 * <p><b>为什么不用 @Transactional 回滚</b>：MySQL 的 DDL 会隐式提交，
 * 且本用例的目的正是「把表建出来供后续 Task 使用」，所以刻意不回滚。
 * 造的数据控制在固定 ID 段内，重复跑不会无限增长。
 */
@SpringBootTest
class AttendanceDdlVerifyTest {

    /** 造数使用的 ID 段起点（固定段，便于重复执行与识别） */
    private static final long SEED_ID_BASE = 1900000000000400000L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("迁移脚本连续执行两次不报错，且三条高频 SQL 的候选索引符合预期")
    void migrationIsIdempotentAndIndexesMatch() throws Exception {
        String ddl = readMigrationDdl();

        // 第一次建表；第二次必须静默跳过（幂等）——报错即验收失败
        jdbcTemplate.execute(ddl);
        jdbcTemplate.execute(ddl);

        seedIfNeeded();

        // ① C 端「我的记录」：等值 user_id + 时间倒序分页
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_attendance "
                        + "WHERE user_id = 1 AND deleted_at = 0 ORDER BY check_in_at DESC LIMIT 10",
                "idx_user_time", "我的记录（按用户 + 时间倒序）");

        // ② admin 按天 + 内外网筛选
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_attendance "
                        + "WHERE attendance_date = '2026-10-04' AND in_lan = 1 AND deleted_at = 0 "
                        + "ORDER BY check_in_at DESC LIMIT 10",
                "idx_date_lan_time", "按天 + 内外网筛选");

        // ③ admin 日报表：按天分组统计
        assertIndexCandidate(
                "EXPLAIN SELECT in_lan, COUNT(*) FROM studio_attendance "
                        + "WHERE attendance_date = '2026-10-04' AND deleted_at = 0 GROUP BY in_lan",
                "idx_date_lan_time", "按天分组统计（日报表）");
    }

    /**
     * 读取迁移脚本并去掉 {@code --} 注释行
     *
     * <p>为什么必须去注释：JdbcTemplate 把整段当作<b>一条</b>语句发给 MySQL，
     * 而连接串没开 {@code allowMultiQueries}，多余的注释会让语句解析失败。
     * 脚本本身只有一条 {@code CREATE TABLE}，去掉注释后正好是一条完整语句。
     */
    private String readMigrationDdl() throws Exception {
        String raw = Files.readString(Path.of("db/migration/20261004_add_attendance.sql"));
        StringBuilder sb = new StringBuilder();
        raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .forEach(line -> sb.append(line).append('\n'));
        return sb.toString();
    }

    /**
     * 造数：让优化器有统计依据
     *
     * <p>10 个用户 × 3 天 × 内外网 = 60 条。用固定 ID 段 + 先删后插，保证重复执行不膨胀。
     */
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
        // 刷新统计信息，否则优化器可能用上一次的旧统计决定执行计划
        jdbcTemplate.execute("ANALYZE TABLE studio_attendance");
    }

    /**
     * 断言 EXPLAIN 的候选索引包含预期索引
     *
     * <p>断言 {@code possible_keys} 而不是 {@code key}：前者证明「索引能被这条查询用上」，
     * 后者是优化器在<b>当前数据量下</b>的选择——表小时它可能合理地选全表扫描。
     * 把「索引设计正确」与「优化器当前选择」绑死，会让用例随数据量抖动而假红。
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

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
 * 验证三件事（对应 验收条款）：
 * - 建表语句连续执行两次不报错：用例把从 db/user.sql 抽出的
 * CREATE TABLE 改写成 CREATE TABLE IF NOT EXISTS 后连跑两次，第二次静默跳过。
 * 注意 MySQL 没有 CREATE INDEX IF NOT EXISTS，所以「索引写在建表语句内」是幂等的前提；
 * - 三条高频 SQL 命中预期索引：见 DESIGN.md 场景 O。
 * 断言的是 possible_keys——它证明「索引对该查询可用且匹配」；
 * 实际是否选用（ key 列）由优化器基于统计信息决定，
 * 表很小时优化器可能选全表扫描，那不是设计问题；
 * - 表结构就位：后续 的实体与 Mapper 依赖这张表。
 *
 * 为什么不用 @Transactional 回滚：MySQL 的 DDL 会隐式提交，
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
    @DisplayName("建表语句连续执行两次不报错，且三条高频 SQL 的候选索引符合预期")
    void migrationIsIdempotentAndIndexesMatch() throws Exception {
        String ddl = readCreateTableDdl();

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
     * 从全量建库脚本里取出考勤表的建表语句
     *
     * db/migration/ 已合并进 db/user.sql（唯一 DDL 源头），因此按表名抽取，
     * 并改写成 CREATE TABLE IF NOT EXISTS：全量脚本靠「先 DROP 再 CREATE」保证可重跑，
     * 用例里不能真去 DROP（那会清空开发库），也不能让第二次执行撞「表已存在」。
     *
     * 为什么必须去注释：JdbcTemplate 把整段当作一条语句发给 MySQL，
     * 而连接串没开 allowMultiQueries，多余的注释会让语句解析失败。
     */
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

    /**
     * 造数：让优化器有统计依据
     *
     * 10 个用户 × 3 天 × 内外网 = 60 条。用固定 ID 段 + 先删后插，保证重复执行不膨胀。
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
     * 断言 possible_keys 而不是 key：前者证明「索引能被这条查询用上」，
     * 后者是优化器在当前数据量下的选择——表小时它可能合理地选全表扫描。
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

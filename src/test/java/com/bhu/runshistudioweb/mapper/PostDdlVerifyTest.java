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
 * 验收：帖子 / 评论 / 举报三张表的迁移脚本幂等 + 四条高频 SQL 命中预期索引
 *
 * author: shaoshing
 *
 * <p>验证两件事：
 * <ol>
 *     <li><b>增量脚本连续执行两次不报错</b>：三句都用 {@code CREATE TABLE IF NOT EXISTS}，
 *     索引一律写在建表语句内部，因此第二次执行整句被跳过。
 *     ⚠️ MySQL 没有 {@code CREATE INDEX IF NOT EXISTS}，
 *     所以「索引写在建表语句内」是幂等的前提，不要把它们拆成独立的 CREATE INDEX；</li>
 *     <li><b>四条高频 SQL 命中预期索引</b>：分别覆盖三张表最典型的查询。</li>
 * </ol>
 *
 * <p><b>为什么按分号拆成多条执行</b>：本脚本建的是<b>三张表</b>（三条 CREATE TABLE），
 * 而连接串没有开 {@code allowMultiQueries}，JdbcTemplate 一次只能发一条语句，
 * 把三条一起提交会解析失败。这与 {@code AttendanceDdlVerifyTest} 不同——那个脚本只有一条建表语句。
 *
 * <p><b>为什么不用 @Transactional 回滚</b>：MySQL 的 DDL 会隐式提交，
 * 且本用例的目的正是「把表建出来供后续业务代码使用」，所以刻意不回滚。
 * 造的数据控制在固定 ID 段内，重复跑不会无限增长。
 */
@SpringBootTest
class PostDdlVerifyTest {

    /** 造数使用的 ID 段起点（与考勤验收测试错开，便于重复执行与识别） */
    private static final long SEED_ID_BASE = 1900000000000500000L;

    /** 造数规模：让优化器有统计依据，又不至于拖慢用例 */
    private static final int SEED_POST_COUNT = 12;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("迁移脚本连续执行两次不报错，且四条高频 SQL 的候选索引符合预期")
    void migrationIsIdempotentAndIndexesMatch() throws Exception {
        List<String> statements = readMigrationStatements();
        // 三张表，一条建表语句都不能少；将来加表时这里会立刻提醒脚本与用例对不上
        assertEquals(3, statements.size(), "迁移脚本里的建表语句数量变了，本用例的造数逻辑需同步调整");

        for (String sql : statements) {
            // 第一次建表；第二次必须静默跳过（幂等）——报错即验收失败
            jdbcTemplate.execute(sql);
            jdbcTemplate.execute(sql);
        }

        seedIfNeeded();

        // ① 公开列表：已通过 + 置顶优先 + 时间倒序
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_post "
                        + "WHERE status = 1 AND deleted_at = 0 ORDER BY pinned DESC, created_at DESC LIMIT 10",
                "idx_status_pinned_time", "公开列表（已通过 + 置顶优先）");

        // ② 审核队列：待审先进先出。这条不能靠 ① 的索引凑合——
        //    它的第二列是 pinned，优化器无法跳过中间列直接按 created_at 排序
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_post "
                        + "WHERE status = 0 AND deleted_at = 0 ORDER BY created_at LIMIT 10",
                "idx_status_time", "帖子审核队列（先进先出）");

        // ③ 某帖的全部评论：一次查完再在内存挂树（避免按父评论逐条查的 N+1）
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_post_comment "
                        + "WHERE post_id = " + SEED_ID_BASE + " AND status = 1 AND deleted_at = 0 ORDER BY id",
                "idx_post_status_id", "某帖的全部已通过评论");

        // ④ 举报待处理列表
        assertIndexCandidate(
                "EXPLAIN SELECT * FROM studio_post_report "
                        + "WHERE status = 0 AND deleted_at = 0 ORDER BY created_at LIMIT 10",
                "idx_status_time", "举报待处理列表");
    }

    /**
     * 读取迁移脚本，去掉 {@code --} 注释行后按分号拆成多条 DDL
     *
     * <p>为什么必须去注释：注释里含有行内说明文字，按分号拆分时可能被误切。
     * 去掉注释行后再拆，得到的正好是三条完整的 CREATE TABLE 语句。
     */
    private List<String> readMigrationStatements() throws Exception {
        String raw = Files.readString(Path.of("db/migration/20261009_add_post.sql"));
        StringBuilder builder = new StringBuilder();
        raw.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .forEach(line -> builder.append(line).append('\n'));

        List<String> statements = new ArrayList<>();
        for (String part : builder.toString().split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    /**
     * 造数：让优化器有统计依据
     *
     * <p>12 个帖子（三种审核状态各若干）+ 每帖 2 条评论（其中一半是回复）+ 6 条举报。
     * 用固定 ID 段 + 先删后插，保证重复执行不膨胀。
     * 三张表都要清：评论与举报通过 post_id 引用帖子，帖子一删它们就成悬空数据。
     */
    private void seedIfNeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_post WHERE id >= " + SEED_ID_BASE, Integer.class);
        if (count != null && count >= SEED_POST_COUNT) {
            return;
        }
        // 从子表往主表清：无外键约束，但保持这个顺序可避免中途读到悬空引用
        jdbcTemplate.update("DELETE FROM studio_post_comment WHERE id >= " + SEED_ID_BASE);
        jdbcTemplate.update("DELETE FROM studio_post_report WHERE id >= " + SEED_ID_BASE);
        jdbcTemplate.update("DELETE FROM studio_post WHERE id >= " + SEED_ID_BASE);

        for (int i = 0; i < SEED_POST_COUNT; i++) {
            long postId = SEED_ID_BASE + i;
            // 状态轮换：0 待审 / 1 已通过 / 2 已驳回，保证三种状态都有样本
            int status = i % 3;
            jdbcTemplate.update(
                    "INSERT INTO studio_post (id, title, summary, cover_image, content, author_id, status,"
                            + " reject_reason, view_count, comment_count, pinned, created_at, updated_at,"
                            + " created_by, updated_by, deleted_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                    postId, "ddl-verify-post-" + i, "验收造数摘要 " + i, null,
                    "正文 ![](/uploads/2026/10/seed.png)", 1 + (i % 2), status,
                    status == 2 ? "验收造数的驳回理由" : null,
                    i, 2, i % 4 == 0 ? 1 : 0);
        }

        // 评论：每个帖子 2 条顶层（带楼层号）+ 1 条回复（parent_id 指向第一条顶层，楼层为 null）
        int c = 0;
        for (int i = 0; i < SEED_POST_COUNT; i++) {
            long postId = SEED_ID_BASE + i;
            long topId = SEED_ID_BASE + 10000 + c++;
            jdbcTemplate.update(
                    "INSERT INTO studio_post_comment (id, post_id, author_id, parent_id, content, floor,"
                            + " status, created_at, updated_at, created_by, updated_by, deleted_at)"
                            + " VALUES (?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                    topId, postId, 1, null, "验收顶层评论 " + i, i + 1, i % 3);
            jdbcTemplate.update(
                    "INSERT INTO studio_post_comment (id, post_id, author_id, parent_id, content, floor,"
                            + " status, created_at, updated_at, created_by, updated_by, deleted_at)"
                            + " VALUES (?,?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                    SEED_ID_BASE + 10000 + c++, postId, 2, topId, "验收回复 " + i, null, i % 3);
        }

        // 举报：三种处理状态各 2 条
        for (int i = 0; i < 6; i++) {
            jdbcTemplate.update(
                    "INSERT INTO studio_post_report (id, target_type, target_id, reporter_id, reason, status,"
                            + " created_at, updated_at, created_by, updated_by, deleted_at)"
                            + " VALUES (?,?,?,?,?,?,NOW(),NOW(),0,0,0)",
                    SEED_ID_BASE + 20000 + i, i % 2 == 0 ? "post" : "comment",
                    SEED_ID_BASE + i, 1 + (i % 2), "验收造数举报理由 " + i, i % 3);
        }

        // 刷新统计信息，否则优化器可能用上一次的旧统计决定执行计划
        jdbcTemplate.execute("ANALYZE TABLE studio_post");
        jdbcTemplate.execute("ANALYZE TABLE studio_post_comment");
        jdbcTemplate.execute("ANALYZE TABLE studio_post_report");
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

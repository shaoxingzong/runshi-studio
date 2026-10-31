package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.mapper.StudioMemberMapper;
import com.bhu.runshistudioweb.mapper.StudioQuizQuestionMapper;
import com.bhu.runshistudioweb.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库管理（管理端）验收测试
 *
 * @author shaoshing
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuizAdminCrudTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private StudioMemberMapper studioMemberMapper;

    @Resource
    private StudioQuizQuestionMapper studioQuizQuestionMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private JsonMapper jsonMapper;

    private QuizTestSupport support;

    /** 测试用的教材名（每个用例独立，避免互相干扰） */
    private String textbook;

    /** 测试用的章节名 */
    private String chapter;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        String suffix = String.valueOf(System.nanoTime() % 100000000);
        textbook = "T34教材_" + suffix;
        chapter = "T34章节_" + suffix;
    }

    // ① 鉴权

    @Test
    @DisplayName("鉴权：匿名 A0201、普通用户 A0301、admin 放行")
    void authorization() {
        String body = support.getBody("/admin/quiz/question/page", null);
        assertEquals("A0201", support.code(body), "匿名访问应 A0201：" + body);

        String userToken = support.tokenOf(support.createUser(UserRoleConstant.USER));
        body = support.getBody("/admin/quiz/question/page", userToken);
        assertEquals("A0301", support.code(body), "普通用户应 A0301：" + body);

        body = support.getBody("/admin/quiz/question/page", support.adminToken());
        assertEquals("00000", support.code(body), "admin 应放行：" + body);
    }

    // ② 新增与写入校验

    @Test
    @DisplayName("新增单选题成功，列表与详情都能查到（含答案）")
    void addSingleQuestion() {
        String token = support.adminToken();
        String body = support.postJson("/admin/quiz/question/add", singleJson("B"), token);
        assertEquals("00000", support.code(body), "新增应成功：" + body);

        String id = support.data(body).asText();
        assertTrue(Long.parseLong(id) > 0, "应返回新题目 ID");

        String detail = support.getBody("/admin/quiz/question/detail?id=" + id, token);
        assertEquals("00000", support.code(detail), "详情应成功：" + detail);
        JsonNode data = support.data(detail);
        assertEquals("B", data.get("correctLabels").asText(), "管理端详情必须带答案");
        assertEquals(3, data.get("options").size(), "singleJson 有 A/B/C 三个选项");
        assertEquals(0, data.get("status").asInt(), "新增默认启用");
    }

    @Test
    @DisplayName("单选传 2 个正确项：A0401（题型标错要在录题时就拦住）")
    void addSingleWithTwoCorrect() {
        String body = support.postJson("/admin/quiz/question/add",
                singleJson("AB"), support.adminToken());
        assertEquals("A0401", support.code(body), "单选题 2 个正确项应被拒：" + body);
        assertTrue(support.message(body).contains("单选题"), "提示应指出是单选：" + body);
    }

    @Test
    @DisplayName("多选只标 1 个正确项：A0401")
    void addMultipleWithOneCorrect() {
        String body = support.postJson("/admin/quiz/question/add",
                multipleJson("A"), support.adminToken());
        assertEquals("A0401", support.code(body), "多选题 1 个正确项应被拒：" + body);
        assertTrue(support.message(body).contains("多选题"), "提示应指出是多选：" + body);
    }

    @Test
    @DisplayName("选项标签跳号：A0401（界面上会显示断档）")
    void addWithSkippedLabel() {
        String json = """
                {"textbookName":"%s","chapterName":"%s","type":0,
                 "stem":"题干","options":[{"label":"A","content":"甲"},{"label":"B","content":"乙"},
                 {"label":"D","content":"丁"}],"correctLabels":"A"}
                """.formatted(textbook, chapter);
        String body = support.postJson("/admin/quiz/question/add", json, support.adminToken());
        assertEquals("A0401", support.code(body), "跳号应被拒：" + body);
        assertTrue(support.message(body).contains("连续"), "提示应说明必须连续：" + body);
    }

    @Test
    @DisplayName("正确项不属于选项：A0401")
    void addWithOutOfRangeAnswer() {
        String body = support.postJson("/admin/quiz/question/add",
                singleJson("D"), support.adminToken());
        // singleJson 只有 A/B/C 三个选项，D 不在其中
        assertEquals("A0401", support.code(body), "正确项超出选项范围应被拒：" + body);
    }

    @Test
    @DisplayName("题型非法：A0401（闭集不能静默当单选）")
    void addWithInvalidType() {
        String json = """
                {"textbookName":"%s","chapterName":"%s","type":9,
                 "stem":"题干","options":[{"label":"A","content":"甲"},{"label":"B","content":"乙"}],
                 "correctLabels":"A"}
                """.formatted(textbook, chapter);
        String body = support.postJson("/admin/quiz/question/add", json, support.adminToken());
        assertEquals("A0401", support.code(body), "非法题型应被拒：" + body);
    }

    // ③ 部分更新

    @Test
    @DisplayName("只改解析：不因缺选项而报错，其余字段保持原值")
    void updateOnlyAnalysis() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));

        String body = support.postJson("/admin/quiz/question/update",
                "{\"id\":" + id + ",\"analysis\":\"新的解析\"}", token);
        assertEquals("00000", support.code(body), "只改解析应成功：" + body);

        JsonNode data = support.data(support.getBody("/admin/quiz/question/detail?id=" + id, token));
        assertEquals("新的解析", data.get("analysis").asText());
        assertEquals("B", data.get("correctLabels").asText(), "答案不该被改动");
        assertEquals(3, data.get("options").size(), "选项不该被清空");
    }

    @Test
    @DisplayName("清空解析：空串落成 null（updateById 会跳过 null，必须显式 set）")
    void clearAnalysis() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));

        String body = support.postJson("/admin/quiz/question/update",
                "{\"id\":" + id + ",\"analysis\":\"\"}", token);
        assertEquals("00000", support.code(body), "清空解析应成功：" + body);

        JsonNode data = support.data(support.getBody("/admin/quiz/question/detail?id=" + id, token));
        assertTrue(data.get("analysis").isNull(), "解析应被清空为 null：" + data);
    }

    @Test
    @DisplayName("部分更新也走整体校验：改成非法的答案仍被拒")
    void updateStillValidates() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));

        String body = support.postJson("/admin/quiz/question/update",
                "{\"id\":" + id + ",\"correctLabels\":\"AB\"}", token);
        assertEquals("A0401", support.code(body), "单选题改成 2 个正确项应被拒：" + body);
    }

    // ④ 判定相关变更 → 重置连对进度

    @Test
    @DisplayName("改答案：重置错题连对进度，但已消解状态保持不变")
    void changeAnswerResetsStreakButKeepsStatus() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);

        // 造两条错题记录：一条待复习（连对 1 次）、一条已消解
        long pendingUser = support.createMember(UserRoleConstant.USER);
        long resolvedUser = support.createMember(UserRoleConstant.USER);
        insertWrong(pendingUser, questionId, 0, 1, 3);
        insertWrong(resolvedUser, questionId, 1, 2, 2);

        // 改答案（B → C）
        String json = """
                {"id":%d,"correctLabels":"C"}
                """.formatted(questionId);
        String body = support.postJson("/admin/quiz/question/update", json, token);
        assertEquals("00000", support.code(body), "改答案应成功：" + body);

        assertEquals(0, wrongStreak(pendingUser, questionId), "待复习条目的连对进度应被重置");
        assertEquals(0, wrongStreak(resolvedUser, questionId), "已消解条目的连对进度也应被重置");
        assertEquals(1, wrongStatus(resolvedUser, questionId),
                "已消解的**状态**必须保持——管理员改题不该否定用户曾经掌握过");
        assertEquals(0, wrongStatus(pendingUser, questionId), "待复习仍是待复习");
    }

    @Test
    @DisplayName("只改选项文字（标签集合不变）：不重置连对进度")
    void changeOptionTextKeepsStreak() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);
        long userId = support.createMember(UserRoleConstant.USER);
        insertWrong(userId, questionId, 0, 1, 3);

        // 只改选项正文，标签仍是 A/B/C，答案仍是 B
        String json = """
                {"id":%d,"options":[
                  {"label":"A","content":"改过的甲"},
                  {"label":"B","content":"改过的乙"},
                  {"label":"C","content":"改过的丙"}],
                 "correctLabels":"B"}
                """.formatted(questionId);
        String body = support.postJson("/admin/quiz/question/update", json, token);
        assertEquals("00000", support.code(body), "改选项文字应成功：" + body);

        assertEquals(1, wrongStreak(userId, questionId),
                "只改文字不影响判定的可解释性，不该重置连对进度");
    }

    @Test
    @DisplayName("增删选项（标签集合变了）：重置连对进度")
    void changeOptionLabelsResetsStreak() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);
        long userId = support.createMember(UserRoleConstant.USER);
        insertWrong(userId, questionId, 0, 1, 3);

        String json = """
                {"id":%d,"options":[
                  {"label":"A","content":"甲"},
                  {"label":"B","content":"乙"},
                  {"label":"C","content":"丙"},
                  {"label":"D","content":"丁"}],
                 "correctLabels":"B"}
                """.formatted(questionId);
        String body = support.postJson("/admin/quiz/question/update", json, token);
        assertEquals("00000", support.code(body), "加选项应成功：" + body);

        assertEquals(0, wrongStreak(userId, questionId), "选项标签集合变了，连对证据应作废");
    }

    // ⑤ 停用 vs 删除

    @Test
    @DisplayName("停用：错题条目保留（可逆），重新启用后错题本恢复")
    void disableKeepsWrongEntries() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);
        long userId = support.createMember(UserRoleConstant.USER);
        String userToken = support.tokenOf(userId);
        insertWrong(userId, questionId, 0, 1, 3);

        // 停用前：错题本有 1 条
        JsonNode before = support.data(support.getBody("/quiz/wrong/page", userToken));
        assertEquals(1, before.get("total").asLong(), "停用前应有 1 条错题");

        support.postJson("/admin/quiz/question/status",
                "{\"id\":" + questionId + ",\"status\":1}", token);

        // 停用后：错题本列表与计数同时减少（不能只少一个）
        JsonNode after = support.data(support.getBody("/quiz/wrong/page", userToken));
        assertEquals(0, after.get("total").asLong(), "停用后错题本不应显示");
        JsonNode overview = support.data(support.getBody("/quiz/overview", userToken));
        assertEquals(0, overview.get("wrongPending").asInt(), "待复习计数也必须同步减少");

        // 条目本身还在（可逆）
        assertEquals(1, wrongRows(questionId), "停用不该删除错题条目，否则重新启用后无法恢复");

        // 重新启用：错题本自动回来
        support.postJson("/admin/quiz/question/status",
                "{\"id\":" + questionId + ",\"status\":0}", token);
        JsonNode restored = support.data(support.getBody("/quiz/wrong/page", userToken));
        assertEquals(1, restored.get("total").asLong(), "重新启用后错题条目应自动回来");
    }

    @Test
    @DisplayName("删除：同事务清理错题条目，作答记录保留")
    void deleteClearsWrongEntries() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);
        long userId = support.createMember(UserRoleConstant.USER);
        insertWrong(userId, questionId, 0, 1, 3);

        String body = support.postJson("/admin/quiz/question/delete",
                "{\"id\":" + questionId + "}", token);
        assertEquals("00000", support.code(body), "删除应成功：" + body);

        assertEquals(0, wrongRows(questionId), "删除题目必须清理错题条目");
        // 逻辑删除：行还在，但 deleted_at != 0
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_question WHERE id = ? AND deleted_at = 0",
                Integer.class, questionId);
        assertEquals(0, alive, "应为逻辑删除");

        String again = support.postJson("/admin/quiz/question/delete",
                "{\"id\":" + questionId + "}", token);
        assertEquals("A0402", support.code(again), "重复删除应 A0402：" + again);
    }

    @Test
    @DisplayName("批量删除：某条不存在不中断，返回实际删除条数")
    void batchDelete() {
        String token = support.adminToken();
        String id1 = createQuestion(token, singleJson("B"));
        String id2 = createQuestion(token, singleJson("B"));

        String body = support.postJson("/admin/quiz/question/batch-delete",
                "{\"ids\":[" + id1 + "," + id2 + ",999999999999999999]}", token);
        assertEquals("00000", support.code(body), "批量删除应成功：" + body);
        assertEquals(2, support.data(body).asInt(), "应删除 2 条（不存在的那条被跳过）");
    }

    // ⑥ 导入

    @Test
    @DisplayName("导入 dryRun：只校验不落库，返回解析数量")
    void importDryRun() {
        String token = support.adminToken();
        String text = "【单选】题干一\\nA. 甲\\nB. 乙*\\n\\n【单选】题干二\\nA. 丙\\nB. 丁*";
        String json = """
                {"textbookName":"%s","chapterName":"%s","text":"%s","dryRun":true}
                """.formatted(textbook, chapter, text);

        String body = support.postJson("/admin/quiz/question/import", json, token);
        assertEquals("00000", support.code(body), "dryRun 应成功：" + body);
        JsonNode data = support.data(body);
        assertEquals(2, data.get("parsed").asInt());
        assertEquals(0, data.get("imported").asInt(), "dryRun 不该落库");
        assertEquals(0, data.get("errors").size(), "不应有错误");

        assertEquals(0, questionCount(), "dryRun 后库里不该有题");
    }

    @Test
    @DisplayName("导入落库：题目归到指定教材章节，题序从 startSeq 递增")
    void importPersists() {
        String token = support.adminToken();
        String text = "【单选】题干一\\nA. 甲\\nB. 乙*\\n\\n【多选】题干二\\nA. 甲*\\nB. 乙*\\nC. 丙";
        String json = """
                {"textbookName":"%s","chapterName":"%s","text":"%s","startSeq":10}
                """.formatted(textbook, chapter, text);

        String body = support.postJson("/admin/quiz/question/import", json, token);
        assertEquals("00000", support.code(body), "导入应成功：" + body);
        assertEquals(2, support.data(body).get("imported").asInt());
        assertEquals(2, questionCount(), "应落库 2 道题");

        Integer firstSeq = jdbcTemplate.queryForObject(
                "SELECT question_seq FROM studio_quiz_question WHERE textbook_name = ? AND stem = '题干一'",
                Integer.class, textbook);
        assertEquals(10, firstSeq, "题序应从 startSeq 开始");
    }

    @Test
    @DisplayName("导入有错：整批不落库，错误带行号")
    void importAllOrNothing() {
        String token = support.adminToken();
        // 第一题合法，第二题缺答案
        String text = "【单选】合法的题\\nA. 甲\\nB. 乙*\\n\\n【单选】没有答案的题\\nA. 丙\\nB. 丁";
        String json = """
                {"textbookName":"%s","chapterName":"%s","text":"%s"}
                """.formatted(textbook, chapter, text);

        String body = support.postJson("/admin/quiz/question/import", json, token);
        assertEquals("00000", support.code(body), "接口本身应成功（错误在 data 里）：" + body);
        JsonNode data = support.data(body);
        assertEquals(0, data.get("imported").asInt(), "有错时不该落库");
        assertTrue(data.get("errors").size() > 0, "应返回错误列表");

        JsonNode error = data.get("errors").get(0);
        assertTrue(error.get("line").asInt() >= 5, "行号应指向第二个块：" + error);
        assertTrue(error.get("message").asText().contains("正确答案"), "错误信息应可读：" + error);

        assertEquals(0, questionCount(), "整批失败时一道题都不该落库");
    }

    // ⑦ 章节 / 教材批量操作

    @Test
    @DisplayName("章节改名：批量 UPDATE，题数不变")
    void renameChapter() {
        String token = support.adminToken();
        createQuestion(token, singleJson("B"));
        createQuestion(token, singleJson("B"));

        String newName = chapter + "_改";
        String json = """
                {"textbookName":"%s","oldName":"%s","newName":"%s"}
                """.formatted(textbook, chapter, newName);
        String body = support.postJson("/admin/quiz/chapter/rename", json, token);
        assertEquals("00000", support.code(body), "改名应成功：" + body);
        assertEquals(2, support.data(body).asInt(), "应影响 2 行");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_question WHERE chapter_name = ?", Integer.class, newName);
        assertEquals(2, count, "两题都应归到新章节名下");
    }

    @Test
    @DisplayName("章节改名到已存在的名字：合并（不报错）")
    void renameChapterMerges() {
        String token = support.adminToken();
        createQuestion(token, singleJson("B"));
        String otherChapter = chapter + "_另一章";
        // 另建一道题放到另一章
        String json = """
                {"textbookName":"%s","chapterName":"%s","type":0,"stem":"另一题的题干",
                 "options":[{"label":"A","content":"甲"},{"label":"B","content":"乙"}],
                 "correctLabels":"A"}
                """.formatted(textbook, otherChapter);
        support.postJson("/admin/quiz/question/add", json, token);

        String rename = """
                {"textbookName":"%s","oldName":"%s","newName":"%s"}
                """.formatted(textbook, otherChapter, chapter);
        String body = support.postJson("/admin/quiz/chapter/rename", rename, token);
        assertEquals("00000", support.code(body), "改名到已存在的章节应合并而不是报错：" + body);

        Integer merged = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_question WHERE textbook_name = ? AND chapter_name = ?",
                Integer.class, textbook, chapter);
        assertEquals(2, merged, "两章应并成一章");
    }

    @Test
    @DisplayName("整章删除：删除该章全部题目并清理错题条目")
    void deleteChapter() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);
        long userId = support.createMember(UserRoleConstant.USER);
        insertWrong(userId, questionId, 0, 1, 3);

        String json = """
                {"textbookName":"%s","chapterName":"%s"}
                """.formatted(textbook, chapter);
        String body = support.postJson("/admin/quiz/chapter/delete", json, token);
        assertEquals("00000", support.code(body), "整章删除应成功：" + body);
        assertEquals(1, support.data(body).asInt(), "应删除 1 题");
        assertEquals(0, wrongRows(questionId), "错题条目应一并清理");
    }

    // ⑧ 教材与章节树

    @Test
    @DisplayName("章节列表（管理端）：含停用题，并单独给启用数")
    void chapterListForAdmin() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        support.postJson("/admin/quiz/question/status", "{\"id\":" + id + ",\"status\":1}", token);

        String body = support.getBody("/admin/quiz/chapter/list", token);
        assertEquals("00000", support.code(body), "章节列表应成功：" + body);

        JsonNode chapterNode = null;
        for (JsonNode node : support.data(body)) {
            if (textbook.equals(node.get("textbookName").asText())
                    && chapter.equals(node.get("name").asText())) {
                chapterNode = node;
                break;
            }
        }
        assertTrue(chapterNode != null, "应能找到本章：" + body);
        assertEquals(1, chapterNode.get("questionCount").asInt(), "管理端口径应含停用题");
        assertEquals(0, chapterNode.get("enabledCount").asInt(), "启用数应为 0");
    }

    @Test
    @DisplayName("C 端章节树：只统计启用题")
    void catalogOnlyCountsEnabled() {
        String token = support.adminToken();
        String id = createQuestion(token, singleJson("B"));
        long questionId = Long.parseLong(id);

        long memberId = support.createMember(UserRoleConstant.USER);
        String memberToken = support.tokenOf(memberId);

        String before = support.getBody("/quiz/catalog", memberToken);
        assertTrue(chapterCountOf(before) > 0, "启用时章节应有题数：" + before);

        support.postJson("/admin/quiz/question/status",
                "{\"id\":" + questionId + ",\"status\":1}", token);

        String after = support.getBody("/quiz/catalog", memberToken);
        // 本章只有这一道题，停用后章节应当从 C 端目录里消失（空章节不展示）
        assertEquals(-1, chapterCountOf(after), "停用后该章节不应再出现在 C 端目录：" + after);
    }

    // 工具方法

    /** 单选题 JSON：三个选项 A/B/C，答案由参数指定 */
    private String singleJson(String correct) {
        return """
                {"textbookName":"%s","chapterName":"%s","type":0,
                 "stem":"T34题干_%d",
                 "options":[{"label":"A","content":"甲"},{"label":"B","content":"乙"},
                            {"label":"C","content":"丙"}],
                 "correctLabels":"%s","analysis":"原始解析"}
                """.formatted(textbook, chapter, System.nanoTime(), correct);
    }

    /** 多选题 JSON：四个选项，答案由参数指定 */
    private String multipleJson(String correct) {
        return """
                {"textbookName":"%s","chapterName":"%s","type":1,
                 "stem":"T34多选_%d",
                 "options":[{"label":"A","content":"甲"},{"label":"B","content":"乙"},
                            {"label":"C","content":"丙"},{"label":"D","content":"丁"}],
                 "correctLabels":"%s"}
                """.formatted(textbook, chapter, System.nanoTime(), correct);
    }

    /** 新增一道题并返回其 ID 字符串 */
    private String createQuestion(String token, String json) {
        String body = support.postJson("/admin/quiz/question/add", json, token);
        assertEquals("00000", support.code(body), "建题应成功：" + body);
        return support.data(body).asText();
    }

    /** 直接插入一条错题记录（不走答题接口，便于精确控制状态） */
    private void insertWrong(long userId, long questionId, int status, int streak, int wrongCount) {
        jdbcTemplate.update("""
                INSERT INTO studio_quiz_wrong
                (id, user_id, question_id, status, wrong_count, correct_streak,
                 last_wrong_at, resolved_at, resolve_type, created_at, updated_at, created_by, updated_by, deleted_at)
                VALUES (?,?,?,?,?,?,NOW(), NULL, NULL, NOW(), NOW(), 0, 0, 0)
                """, System.nanoTime(), userId, questionId, status, wrongCount, streak);
    }

    /** 读某用户对某题的错题连对进度 */
    private int wrongStreak(long userId, long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT correct_streak FROM studio_quiz_wrong WHERE user_id = ? AND question_id = ? AND deleted_at = 0",
                Integer.class, userId, questionId);
        return value == null ? -1 : value;
    }

    /** 读某用户对某题的错题状态 */
    private int wrongStatus(long userId, long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT status FROM studio_quiz_wrong WHERE user_id = ? AND question_id = ? AND deleted_at = 0",
                Integer.class, userId, questionId);
        return value == null ? -1 : value;
    }

    /** 某题的有效错题条目数 */
    private int wrongRows(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_wrong WHERE question_id = ? AND deleted_at = 0",
                Integer.class, questionId);
        return value == null ? 0 : value;
    }

    /** 本用例教材下的有效题目数 */
    private int questionCount() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_question WHERE textbook_name = ? AND deleted_at = 0",
                Integer.class, textbook);
        return value == null ? 0 : value;
    }

    /** 在 C 端目录响应里找本章的题数；找不到返回 -1 */
    private int chapterCountOf(String catalogBody) {
        for (JsonNode textbookNode : support.data(catalogBody)) {
            if (!textbook.equals(textbookNode.get("name").asText())) {
                continue;
            }
            for (JsonNode chapterNode : textbookNode.get("chapters")) {
                if (chapter.equals(chapterNode.get("name").asText())) {
                    return chapterNode.get("questionCount").asInt();
                }
            }
        }
        return -1;
    }
}

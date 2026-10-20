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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刷题流程验收测试（C 端：三种模式、判分、跳过、错题本、概览）
 *
 * author: shaoshing
 *
 * <p><b>覆盖的规则</b>：
 * <ul>
 *     <li><b>三种模式</b>：顺序（排除已作答）、错题重刷（只出待复习）、随机（范围内不重复）；</li>
 *     <li><b>判分</b>：多选不给部分分（漏选/多选/错选均判错）、单选传多标签是 A0401 而不是判错；</li>
 *     <li><b>跳过</b>：不记错、不计入正确率、响应<b>不含答案</b>；</li>
 *     <li><b>错题本状态机</b>：答错入本 → 连对 2 次消解 → 再答错自动回待复习；</li>
 *     <li><b>幂等</b>：重复提交只落一条、返回首次判定、统计不翻倍；</li>
 *     <li><b>概览口径</b>：累计作答不含跳过、0 次作答时正确率为 null。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code @Transactional}</b>：答题的写操作（作答记录 + 错题本）都在事务内，
 * 测试回滚即可撤销。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuizPracticeTest {

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

    private String textbook;

    private String chapter;

    /** 测试用的成员 token */
    private String memberToken;

    /** 测试用的成员账号 ID */
    private long memberId;

    @BeforeEach
    void setUp() {
        support = new QuizTestSupport(mockMvc, sysUserMapper, studioMemberMapper, jdbcTemplate, jsonMapper);
        String suffix = String.valueOf(System.nanoTime() % 100000000);
        textbook = "T34流程教材_" + suffix;
        chapter = "T34流程章节_" + suffix;
        memberId = support.createMember(UserRoleConstant.USER);
        memberToken = support.tokenOf(memberId);
    }

    // ==================== ① 准入 ====================

    @Test
    @DisplayName("准入：非成员（只有账号没有档案）一律 A0301，连教材列表都不给")
    void nonMemberIsRejected() {
        long plainUserId = support.createUser(UserRoleConstant.USER);
        String plainToken = support.tokenOf(plainUserId);

        String body = support.getBody("/quiz/catalog", plainToken);
        assertEquals("A0301", support.code(body), "非成员应 A0301：" + body);
        assertTrue(support.message(body).contains("成员"), "提示应说明需要成员身份：" + body);

        body = support.getBody("/quiz/overview", plainToken);
        assertEquals("A0301", support.code(body), "非成员看概览也应 A0301：" + body);
    }

    @Test
    @DisplayName("准入：未登录 A0201")
    void anonymousIsRejected() {
        String body = support.getBody("/quiz/catalog", null);
        assertEquals("A0201", support.code(body), "未登录应 A0201：" + body);
    }

    // ==================== ② 顺序模式 ====================

    @Test
    @DisplayName("顺序模式：按题序出题，第二轮排除已作答的题")
    void sequentialExcludesAnswered() {
        createQuestion("顺序题一", 0, "A", 1);
        createQuestion("顺序题二", 0, "B", 2);
        createQuestion("顺序题三", 0, "A", 3);

        // 第一轮：三题都未作答，size=2 → 拿题序最小的两题
        JsonNode batch = startBatch(0, 2);
        JsonNode questions = batch.get("questions");
        assertEquals(2, questions.size(), "应按 size 截断");
        assertEquals("顺序题一", questions.get(0).get("stem").asText(), "必须按题序升序");
        assertEquals("顺序题二", questions.get(1).get("stem").asText());

        // 答完第一题（答对）
        long questionId = questions.get(0).get("id").asLong();
        String verdict = answer(batch.get("batchId").asText(), questionId, "A");
        assertEquals("00000", support.code(verdict), "作答应成功：" + verdict);

        // 第二轮：第一题已被排除，应从第二题开始
        JsonNode second = startBatch(0, 2);
        assertEquals("顺序题二", second.get("questions").get(0).get("stem").asText(),
                "已作答的题不该再出现");
    }

    @Test
    @DisplayName("顺序模式：全部刷完时 A0402 并提示可重刷")
    void sequentialAllDone() {
        createQuestion("唯一的一道题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        answer(batch.get("batchId").asText(), batch.get("questions").get(0).get("id").asLong(), "A");

        String body = support.postJson("/quiz/batch",
                "{\"mode\":0,\"textbookName\":\"" + textbook + "\",\"chapterName\":\"" + chapter + "\"}",
                memberToken);
        assertEquals("A0402", support.code(body), "刷完应 A0402：" + body);
        assertTrue(support.message(body).contains("重新刷"), "提示应告诉用户可以重刷：" + body);
    }

    @Test
    @DisplayName("顺序模式：restart=true 时忽略排除条件，从头再刷")
    void sequentialRestart() {
        createQuestion("重刷题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        answer(batch.get("batchId").asText(), batch.get("questions").get(0).get("id").asLong(), "A");

        String body = support.postJson("/quiz/batch",
                "{\"mode\":0,\"textbookName\":\"" + textbook + "\",\"chapterName\":\"" + chapter
                        + "\",\"restart\":true}", memberToken);
        assertEquals("00000", support.code(body), "restart 应能重刷：" + body);
        assertEquals(1, support.data(body).get("questions").size());
    }

    @Test
    @DisplayName("顺序模式：不选章节时 A0401")
    void sequentialRequiresChapter() {
        String body = support.postJson("/quiz/batch", "{\"mode\":0}", memberToken);
        assertEquals("A0401", support.code(body), "顺序模式缺章节应 A0401：" + body);
    }

    // ==================== ③ 判分 ====================

    @Test
    @DisplayName("单选答对：result=1，返回正确答案与解析")
    void singleCorrect() {
        long questionId = createQuestion("单选题干", 0, "B", 1);
        JsonNode batch = startBatch(0, 10);
        String verdictBody = answer(batch.get("batchId").asText(), questionId, "b");

        assertEquals("00000", support.code(verdictBody), "作答应成功：" + verdictBody);
        JsonNode verdict = support.data(verdictBody);
        assertEquals(1, verdict.get("result").asInt(), "应判对");
        assertEquals("B", verdict.get("correctLabels").asText(), "应返回正确答案");
        assertEquals("B", verdict.get("chosenLabels").asText(), "小写输入应被归一化成大写");
        assertFalse(verdict.get("analysis").isNull(), "应返回解析");
        assertEquals("none", verdict.get("wrongBookAction").asText(), "答对且不在错题本，无副作用");
    }

    @Test
    @DisplayName("单选答错：result=2，进错题本（action=added）")
    void singleWrongAddsToWrongBook() {
        long questionId = createQuestion("单选题干", 0, "B", 1);
        JsonNode batch = startBatch(0, 10);
        JsonNode verdict = support.data(answer(batch.get("batchId").asText(), questionId, "A"));

        assertEquals(2, verdict.get("result").asInt(), "应判错");
        assertEquals("added", verdict.get("wrongBookAction").asText(), "首次答错应加入错题本");
        assertEquals(1, wrongCount(questionId), "错题本应有 1 条");
    }

    @Test
    @DisplayName("多选漏选：判错（不给部分分）")
    void multipleMissingOneIsWrong() {
        long questionId = createQuestion("多选题干", 1, "AB", 1);
        JsonNode batch = startBatch(0, 10);
        JsonNode verdict = support.data(answer(batch.get("batchId").asText(), questionId, "A"));

        assertEquals(2, verdict.get("result").asInt(),
                "漏选必须判错——政治多选的评分规则就是全对才得分");
        assertEquals("AB", verdict.get("correctLabels").asText());
    }

    @Test
    @DisplayName("多选多选一个错误项：判错")
    void multipleWithExtraIsWrong() {
        long questionId = createQuestion("多选题干", 1, "AB", 1);
        JsonNode batch = startBatch(0, 10);
        JsonNode verdict = support.data(answer(batch.get("batchId").asText(), questionId, "ABC"));

        assertEquals(2, verdict.get("result").asInt(), "多选错误项应判错");
    }

    @Test
    @DisplayName("多选全对：result=1，且顺序不影响判定")
    void multipleCorrectIgnoresOrder() {
        long questionId = createQuestion("多选题干", 1, "AB", 1);
        JsonNode batch = startBatch(0, 10);
        JsonNode verdict = support.data(answer(batch.get("batchId").asText(), questionId, "ba"));

        assertEquals(1, verdict.get("result").asInt(), "顺序不同是同一个集合，必须判对");
        assertEquals("AB", verdict.get("chosenLabels").asText(), "所选应被归一化为升序");
    }

    @Test
    @DisplayName("单选传两个标签：A0401（非法请求，不是判错）")
    void singleWithTwoLabelsIsRejected() {
        long questionId = createQuestion("单选题干", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        String body = answer(batch.get("batchId").asText(), questionId, "AB");

        assertEquals("A0401", support.code(body), "单选传 2 个标签应 A0401：" + body);
        assertTrue(support.message(body).contains("单选"), "提示应指出是单选：" + body);
        assertEquals(0, answerCount(questionId), "被拒的请求不该落库");
    }

    @Test
    @DisplayName("标签不在选项内：A0401")
    void labelOutOfOptionsIsRejected() {
        long questionId = createQuestion("单选题干", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        String body = answer(batch.get("batchId").asText(), questionId, "D");

        assertEquals("A0401", support.code(body), "不存在的选项应 A0401：" + body);
    }

    // ==================== ④ 跳过 ====================

    @Test
    @DisplayName("跳过：落一条 result=3 的记录，不写错题本，响应不含答案")
    void skipDoesNotTouchWrongBook() {
        long questionId = createQuestion("跳过的题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        String batchId = batch.get("batchId").asText();

        String body = support.postJson("/quiz/skip",
                "{\"batchId\":" + batchId + ",\"questionId\":" + questionId + "}", memberToken);
        assertEquals("00000", support.code(body), "跳过应成功：" + body);
        assertTrue(support.data(body).asBoolean(), "应返回 true");

        // 响应体里不能出现任何答案痕迹
        assertFalse(body.contains("correctLabels"), "跳过响应绝不能含正确答案");
        assertFalse(body.contains("analysis"), "跳过响应绝不能含解析");

        assertEquals(1, skipCount(questionId), "应落一条跳过记录");
        assertEquals(0, wrongCount(questionId), "跳过不记错");
    }

    @Test
    @DisplayName("跳过：不计入正确率（分母只含作答）")
    void skipNotCountedInAccuracy() {
        long questionId = createQuestion("跳过的题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        support.postJson("/quiz/skip",
                "{\"batchId\":" + batch.get("batchId").asText() + ",\"questionId\":" + questionId + "}",
                memberToken);

        JsonNode overview = support.data(support.getBody("/quiz/overview", memberToken));
        assertEquals(0, overview.get("answeredTotal").asInt(), "跳过不算作答");
        assertTrue(overview.get("accuracy").isNull(), "0 次作答时正确率应为 null（前端显示 --）");
    }

    @Test
    @DisplayName("跳过：下一轮顺序刷题仍会遇到它（跳过不是「已作答」）")
    void skippedQuestionAppearsAgain() {
        long questionId = createQuestion("跳过的题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        support.postJson("/quiz/skip",
                "{\"batchId\":" + batch.get("batchId").asText() + ",\"questionId\":" + questionId + "}",
                memberToken);

        JsonNode second = startBatch(0, 10);
        assertEquals(1, second.get("questions").size(), "跳过的题下一轮还应该出现");
        assertEquals(questionId, second.get("questions").get(0).get("id").asLong());
    }

    // ==================== ⑤ 错题本状态机 ====================

    @Test
    @DisplayName("错题本状态机：答错入本 → 连对 2 次消解 → 再答错回待复习")
    void wrongBookStateMachine() {
        long questionId = createQuestion("状态机题", 0, "A", 1);

        // ① 答错 → 入本
        JsonNode verdict = answerInNewBatch(questionId, "B");
        assertEquals("added", verdict.get("wrongBookAction").asText());
        assertEquals(1, pendingWrong(questionId), "应进待复习");

        // ② 第一次答对 → 进度 +1，还差 1 次
        verdict = answerInNewBatch(questionId, "A");
        assertEquals("progress", verdict.get("wrongBookAction").asText(), "未达阈值应只是进度");
        assertEquals(1, verdict.get("remainingStreak").asInt(), "还差 1 次");
        assertEquals(1, pendingWrong(questionId), "仍在待复习");

        // ③ 第二次答对 → 消解
        verdict = answerInNewBatch(questionId, "A");
        assertEquals("resolved", verdict.get("wrongBookAction").asText(), "连对 2 次应消解");
        assertEquals(0, pendingWrong(questionId), "不应再出现在待复习");
        assertEquals(1, resolvedWrong(questionId), "应计入已消解");

        // ④ 消解后再答错 → 自动回待复习
        verdict = answerInNewBatch(questionId, "B");
        assertEquals("reopened", verdict.get("wrongBookAction").asText(), "消解后再错应回到待复习");
        assertEquals(1, pendingWrong(questionId), "应重新出现在待复习");
        assertEquals(0, resolvedWrong(questionId), "已消解计数应减少（是当前状态，不是历史累计）");
    }

    @Test
    @DisplayName("错题本：手动标记已掌握，且幂等（不改消解时间）")
    void manualResolve() {
        long questionId = createQuestion("手动消解题", 0, "A", 1);
        answerInNewBatch(questionId, "B");

        JsonNode page = support.data(support.getBody("/quiz/wrong/page", memberToken));
        assertEquals(1, page.get("total").asLong(), "应有 1 条错题");
        long wrongId = page.get("records").get(0).get("id").asLong();

        String body = support.postJson("/quiz/wrong/resolve", "{\"id\":" + wrongId + "}", memberToken);
        assertEquals("00000", support.code(body), "标记应成功：" + body);
        assertEquals(0, pendingWrong(questionId), "应移出待复习");
        assertEquals(1, resolvedWrong(questionId));

        // 幂等：再点一次仍成功，且消解时间不变
        String firstResolvedAt = resolvedAt(wrongId);
        body = support.postJson("/quiz/wrong/resolve", "{\"id\":" + wrongId + "}", memberToken);
        assertEquals("00000", support.code(body), "重复标记应幂等成功：" + body);
        assertEquals(firstResolvedAt, resolvedAt(wrongId), "重复标记不该改消解时间");
    }

    @Test
    @DisplayName("错题本：访问他人的条目 A0301（不暴露存在性）")
    void cannotAccessOthersWrongEntry() {
        long questionId = createQuestion("别人的错题", 0, "A", 1);
        answerInNewBatch(questionId, "B");

        JsonNode page = support.data(support.getBody("/quiz/wrong/page", memberToken));
        long wrongId = page.get("records").get(0).get("id").asLong();

        long otherId = support.createMember(UserRoleConstant.USER);
        String otherToken = support.tokenOf(otherId);
        String body = support.getBody("/quiz/wrong/detail?id=" + wrongId, otherToken);
        assertEquals("A0301", support.code(body), "访问他人错题应 A0301：" + body);

        body = support.postJson("/quiz/wrong/resolve", "{\"id\":" + wrongId + "}", otherToken);
        assertEquals("A0301", support.code(body), "改他人错题应 A0301：" + body);
    }

    @Test
    @DisplayName("错题本列表不含答案，详情才含（且带「你上次选了什么」）")
    void wrongListHidesAnswerDetailShows() {
        long questionId = createQuestion("错题详情题", 0, "A", 1);
        answerInNewBatch(questionId, "C");

        String listBody = support.getBody("/quiz/wrong/page", memberToken);
        assertFalse(listBody.contains("correctLabels"), "错题列表绝不能含答案：" + listBody);
        assertFalse(listBody.contains("analysis"), "错题列表绝不能含解析");

        long wrongId = support.data(listBody).get("records").get(0).get("id").asLong();
        JsonNode detail = support.data(support.getBody("/quiz/wrong/detail?id=" + wrongId, memberToken));
        assertEquals("A", detail.get("correctLabels").asText(), "详情应含正确答案");
        assertEquals("C", detail.get("lastChosenLabels").asText(), "应带上你上次选了什么");
        assertEquals(3, detail.get("options").size(), "应含选项");
    }

    // ==================== ⑥ 错题模式 ====================

    @Test
    @DisplayName("错题模式：只出待复习的错题")
    void wrongModeOnlyPending() {
        long wrongQuestion = createQuestion("会答错的题", 0, "A", 1);
        long rightQuestion = createQuestion("会答对的题", 0, "A", 2);
        answerInNewBatch(wrongQuestion, "B");
        answerInNewBatch(rightQuestion, "A");

        JsonNode batch = support.data(support.postJson("/quiz/batch", "{\"mode\":1}", memberToken));
        assertEquals(1, batch.get("questions").size(), "只应出那一道待复习的错题");
        assertEquals(wrongQuestion, batch.get("questions").get(0).get("id").asLong());
    }

    @Test
    @DisplayName("错题模式：错题本为空时 A0402")
    void wrongModeEmpty() {
        String body = support.postJson("/quiz/batch", "{\"mode\":1}", memberToken);
        assertEquals("A0402", support.code(body), "错题本为空应 A0402：" + body);
    }

    // ==================== ⑦ 随机模式 ====================

    @Test
    @DisplayName("随机模式：范围内抽题、数量正确、一轮内不重复")
    void randomMode() {
        for (int i = 1; i <= 10; i++) {
            createQuestion("随机题" + i, 0, "A", i);
        }
        JsonNode batch = support.data(support.postJson("/quiz/batch",
                "{\"mode\":2,\"textbookName\":\"" + textbook + "\",\"size\":5}", memberToken));

        JsonNode questions = batch.get("questions");
        assertEquals(5, questions.size(), "应按 size 抽题");
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : questions) {
            ids.add(node.get("id").asLong());
        }
        assertEquals(5, ids.stream().distinct().count(), "一轮内不能出现重复的题");
    }

    @Test
    @DisplayName("随机模式：范围内没有题时 A0402")
    void randomModeEmpty() {
        String body = support.postJson("/quiz/batch",
                "{\"mode\":2,\"textbookName\":\"不存在的教材\"}", memberToken);
        assertEquals("A0402", support.code(body), "无题应 A0402：" + body);
    }

    // ==================== ⑧ 幂等 ====================

    @Test
    @DisplayName("幂等：同一题重复提交只落一条记录，返回首次判定，统计不翻倍")
    void duplicateSubmitIsIdempotent() {
        long questionId = createQuestion("幂等题", 0, "A", 1);
        JsonNode batch = startBatch(0, 10);
        String batchId = batch.get("batchId").asText();

        JsonNode first = support.data(answer(batchId, questionId, "B"));
        assertEquals(2, first.get("result").asInt(), "首次应判错");
        assertEquals(1, first.get("answeredTotal").asInt(), "累计作答应为 1");

        // 第二次提交（断网重试 / 双击）：必须原样返回首次判定
        JsonNode second = support.data(answer(batchId, questionId, "A"));
        assertEquals("00000", support.code(answer(batchId, questionId, "A")), "重复提交应成功而非报错");
        assertEquals(2, second.get("result").asInt(),
                "必须返回**首次判定**（错），而不是按新答案重判");
        assertEquals(1, second.get("answeredTotal").asInt(), "统计不能翻倍");
        assertEquals(1, answerCount(questionId), "只能有一条作答记录");
        assertEquals(1, wrongCount(questionId), "错题本不能被重复计数");
    }

    // ==================== ⑨ 概览口径 ====================

    @Test
    @DisplayName("概览：累计作答与正确数正确，0 次作答时正确率为 null")
    void overviewCounts() {
        JsonNode empty = support.data(support.getBody("/quiz/overview", memberToken));
        assertEquals(0, empty.get("answeredTotal").asInt());
        assertTrue(empty.get("accuracy").isNull(), "0 次作答时正确率应为 null");

        long q1 = createQuestion("概览题一", 0, "A", 1);
        long q2 = createQuestion("概览题二", 0, "A", 2);
        answerInNewBatch(q1, "A");
        answerInNewBatch(q2, "B");

        JsonNode overview = support.data(support.getBody("/quiz/overview", memberToken));
        assertEquals(2, overview.get("answeredTotal").asInt(), "两题各答一次");
        assertEquals(1, overview.get("correctTotal").asInt(), "只答对一题");
        assertEquals(0.5, overview.get("accuracy").asDouble(), 0.0001, "正确率应为 0.5");
        assertEquals(1, overview.get("wrongPending").asInt(), "一道待复习");
    }

    // ==================== ⑩ 出题响应不含答案 ====================

    @Test
    @DisplayName("出题响应结构上没有答案字段（判分在服务端）")
    void batchResponseHasNoAnswer() {
        createQuestion("出题脱敏题", 0, "A", 1);
        String body = support.postJson("/quiz/batch",
                "{\"mode\":0,\"textbookName\":\"" + textbook + "\",\"chapterName\":\"" + chapter + "\"}",
                memberToken);

        assertEquals("00000", support.code(body), "开批应成功：" + body);
        assertFalse(body.contains("correctLabels"), "题目响应绝不能含正确答案：" + body);
        assertFalse(body.contains("analysis"), "题目响应绝不能含解析");
        assertFalse(body.contains("isCorrect"), "选项绝不能带正确性标记");
    }

    // ==================== 工具方法 ====================

    /** 建一道题并返回 ID */
    private long createQuestion(String stem, int type, String correct, int seq) {
        String token = support.adminToken();
        String json = """
                {"textbookName":"%s","chapterName":"%s","type":%d,"stem":"%s",
                 "options":[{"label":"A","content":"选项甲"},{"label":"B","content":"选项乙"},
                            {"label":"C","content":"选项丙"}],
                 "correctLabels":"%s","analysis":"这是解析","questionSeq":%d}
                """.formatted(textbook, chapter, type, stem, correct, seq);
        String body = support.postJson("/admin/quiz/question/add", json, token);
        assertEquals("00000", support.code(body), "建题应成功：" + body);
        return Long.parseLong(support.data(body).asText());
    }

    /** 开一批顺序题 */
    private JsonNode startBatch(int mode, int size) {
        String body = support.postJson("/quiz/batch",
                "{\"mode\":" + mode + ",\"textbookName\":\"" + textbook
                        + "\",\"chapterName\":\"" + chapter + "\",\"size\":" + size + "}", memberToken);
        assertEquals("00000", support.code(body), "开批应成功：" + body);
        return support.data(body);
    }

    /** 提交答案，返回原始响应体 */
    private String answer(String batchId, long questionId, String labels) {
        return support.postJson("/quiz/answer",
                "{\"batchId\":" + batchId + ",\"questionId\":" + questionId
                        + ",\"chosenLabels\":\"" + labels + "\"}", memberToken);
    }

    /** 开一批、答一题、返回判定（每次用新批次，便于重复答同一题） */
    private JsonNode answerInNewBatch(long questionId, String labels) {
        // 错题模式只会出待复习的题，所以这里用随机模式 + 单题范围来保证能拿到目标题
        String batchBody = support.postJson("/quiz/batch",
                "{\"mode\":2,\"textbookName\":\"" + textbook + "\",\"size\":100}", memberToken);
        assertEquals("00000", support.code(batchBody), "开批应成功：" + batchBody);
        String batchId = support.data(batchBody).get("batchId").asText();

        String verdictBody = answer(batchId, questionId, labels);
        assertEquals("00000", support.code(verdictBody), "作答应成功：" + verdictBody);
        return support.data(verdictBody);
    }

    private int wrongCount(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_wrong WHERE question_id = ? AND user_id = ? AND deleted_at = 0",
                Integer.class, questionId, memberId);
        return value == null ? 0 : value;
    }

    private int pendingWrong(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_wrong WHERE question_id = ? AND user_id = ?"
                        + " AND status = 0 AND deleted_at = 0",
                Integer.class, questionId, memberId);
        return value == null ? 0 : value;
    }

    private int resolvedWrong(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_wrong WHERE question_id = ? AND user_id = ?"
                        + " AND status = 1 AND deleted_at = 0",
                Integer.class, questionId, memberId);
        return value == null ? 0 : value;
    }

    private int answerCount(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_answer WHERE question_id = ? AND user_id = ?"
                        + " AND result IN (1,2) AND deleted_at = 0",
                Integer.class, questionId, memberId);
        return value == null ? 0 : value;
    }

    private int skipCount(long questionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM studio_quiz_answer WHERE question_id = ? AND user_id = ?"
                        + " AND result = 3 AND deleted_at = 0",
                Integer.class, questionId, memberId);
        return value == null ? 0 : value;
    }

    private String resolvedAt(long wrongId) {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(resolved_at AS CHAR) FROM studio_quiz_wrong WHERE id = ?",
                String.class, wrongId);
    }
}

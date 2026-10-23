package com.bhu.runshistudioweb.manager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 选题器验收测试（纯单测：不启 Spring、不连数据库）
//
// 三种模式的边界全部在这里覆盖：刷完、题量不足、随机不重复、随机可复现。
// 这些是「用户能不能顺利刷完一轮」的直接决定因素，比 CRUD 更值得写测试。
class QuizQuestionSamplerTest {

    private static List<Long> ids(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    // ==================== takeOrdered ====================

    @Test
    @DisplayName("顺序取前 N 个：保持候选原顺序，不重排")
    void takeOrderedKeepsOrder() {
        // 顺序模式的语义是「按题序往后走」，重排会让它变成随机模式
        assertEquals(ids(1, 2, 3), QuizQuestionSampler.takeOrdered(ids(1, 2, 3, 4, 5), 3));
        assertEquals(ids(3, 1, 2), QuizQuestionSampler.takeOrdered(ids(3, 1, 2), 3),
                "候选顺序就是业务顺序，不能被改");
    }

    @Test
    @DisplayName("题量不足：按实际数量返回，不补题、不报错")
    void takeOrderedFewerThanSize() {
        // 「本章只剩 3 题」是正常状态。补题会把别的章节的题混进来（用户以为串章了），
        // 报错则会让用户以为系统坏了
        assertEquals(ids(7, 8), QuizQuestionSampler.takeOrdered(ids(7, 8), 20));
    }

    @Test
    @DisplayName("空候选 / 非法题量：返回空列表，不返回 null")
    void takeOrderedEmpty() {
        assertTrue(QuizQuestionSampler.takeOrdered(null, 10).isEmpty());
        assertTrue(QuizQuestionSampler.takeOrdered(List.of(), 10).isEmpty());
        assertTrue(QuizQuestionSampler.takeOrdered(ids(1, 2), 0).isEmpty());
        assertTrue(QuizQuestionSampler.takeOrdered(ids(1, 2), -5).isEmpty());
    }

    // ==================== takeRandom ====================

    @Test
    @DisplayName("随机取题：数量正确且一轮内不重复")
    void takeRandomNoDuplicates() {
        List<Long> candidates = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            candidates.add(i);
        }
        List<Long> picked = QuizQuestionSampler.takeRandom(candidates, 20, null);

        assertEquals(20, picked.size());
        // 洗牌后截断天然保证不重复；用 Set 断言把这个性质钉死
        assertEquals(20, new HashSet<>(picked).size(), "同一轮里出现了重复的题");
    }

    @Test
    @DisplayName("随机取题：题量不足时返回全部候选")
    void takeRandomFewerCandidates() {
        List<Long> picked = QuizQuestionSampler.takeRandom(ids(1, 2, 3), 20, null);
        assertEquals(3, picked.size());
        assertEquals(Set.of(1L, 2L, 3L), new HashSet<>(picked));
    }

    @Test
    @DisplayName("随机取题：同一 seed 可复现（否则随机逻辑只能测弱断言）")
    void takeRandomReproducible() {
        List<Long> candidates = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            candidates.add(i);
        }
        List<Long> first = QuizQuestionSampler.takeRandom(candidates, 10, new Random(42));
        List<Long> second = QuizQuestionSampler.takeRandom(candidates, 10, new Random(42));
        assertEquals(first, second, "同一 seed 必须得到同一结果");
    }

    @Test
    @DisplayName("随机取题：不改动调用方的集合（Service 可能还要用它做别的判断）")
    void takeRandomDoesNotMutateInput() {
        List<Long> candidates = ids(1, 2, 3, 4, 5);
        List<Long> snapshot = new ArrayList<>(candidates);
        QuizQuestionSampler.takeRandom(candidates, 3, new Random(1));
        assertEquals(snapshot, candidates, "调用方的候选列表被就地洗牌了");
    }

    // ==================== excludeAnswered ====================

    @Test
    @DisplayName("排除已作答：保留原顺序")
    void excludeAnsweredKeepsOrder() {
        List<Long> remaining = QuizQuestionSampler.excludeAnswered(ids(1, 2, 3, 4, 5), ids(2, 4));
        assertEquals(ids(1, 3, 5), remaining);
    }

    @Test
    @DisplayName("排除已作答：全部答完时返回空列表（由 Service 转成「本章已刷完」）")
    void excludeAnsweredAll() {
        assertTrue(QuizQuestionSampler.excludeAnswered(ids(1, 2), ids(1, 2)).isEmpty());
    }

    @Test
    @DisplayName("排除已作答：没有已答记录时原样返回")
    void excludeAnsweredNone() {
        assertEquals(ids(1, 2, 3), QuizQuestionSampler.excludeAnswered(ids(1, 2, 3), null));
        assertEquals(ids(1, 2, 3), QuizQuestionSampler.excludeAnswered(ids(1, 2, 3), Set.of()));
    }

    @Test
    @DisplayName("排除已作答：候选为空时返回空列表")
    void excludeAnsweredEmptyCandidates() {
        assertTrue(QuizQuestionSampler.excludeAnswered(null, ids(1)).isEmpty());
        assertTrue(QuizQuestionSampler.excludeAnswered(List.of(), ids(1)).isEmpty());
    }

    // ==================== resolveSize ====================

    @Test
    @DisplayName("题量收敛：不传用默认值，超限收敛到上限，非法值用默认值")
    void resolveSize() {
        assertEquals(20, QuizQuestionSampler.resolveSize(null, 20, 100));
        assertEquals(20, QuizQuestionSampler.resolveSize(0, 20, 100), "0 视为「没传」");
        assertEquals(20, QuizQuestionSampler.resolveSize(-3, 20, 100));
        assertEquals(50, QuizQuestionSampler.resolveSize(50, 20, 100));
        // 超限收敛而不是报错：与分页参数同一处理方式
        assertEquals(100, QuizQuestionSampler.resolveSize(99999, 20, 100));
    }

    @Test
    @DisplayName("题量收敛：默认值本身超过上限时也要被收敛")
    void resolveSizeDefaultAboveMax() {
        // 防御性用例：如果运维把 default-size 配成 200、max-size 配成 50，
        // 不收敛就会绕过上限
        assertEquals(50, QuizQuestionSampler.resolveSize(null, 200, 50));
    }

    @Test
    @DisplayName("题量收敛：上限非法（<=0）时至少给 1，不能返回 0 题")
    void resolveSizeDegenerateMax() {
        assertEquals(1, QuizQuestionSampler.resolveSize(null, 20, 0));
        assertEquals(1, QuizQuestionSampler.resolveSize(5, 20, -1));
    }

    @Test
    @DisplayName("首页错题预览数量是固定值")
    void homePreviewSize() {
        assertEquals(5, QuizQuestionSampler.homePreviewSize());
        assertFalse(QuizQuestionSampler.homePreviewSize() <= 0);
    }
}

package com.bhu.runshistudioweb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC 自动机验收测试（纯单测：不启 Spring、不读文件、不连数据库）
 *
 * author: shaoshing
 *
 * <p>本类守的是「多模式匹配」这条底线的正确性，重点不在命中与否，
 * 而在<b>各种边界下的行为是否符合预期</b>：
 * <ul>
 *     <li>重叠词与前缀词<b>都要</b>命中（这是「output 合并」这一步存在的理由，
 *     漏掉合并就只会命中较长的那个）；</li>
 *     <li>归一化后位置变了，返回的区间必须仍是<b>原文</b>的索引（否则前端高亮会错位）；</li>
 *     <li>空词绝不能「匹配一切」——空模式在每个位置都会命中，是个隐蔽且后果严重的坑。</li>
 * </ul>
 */
class AcAutomatonTest {

    /** 取出命中里的词，便于断言 */
    private static List<String> words(List<AcAutomaton.Hit> hits) {
        return hits.stream().map(AcAutomaton.Hit::word).toList();
    }

    @Test
    @DisplayName("基础：含有词库中的词就命中，没有就不命中")
    void basicMatch() {
        AcAutomaton ac = AcAutomaton.build(List.of("敏感词"));

        assertTrue(ac.contains("这是一段含有敏感词的话"));
        assertFalse(ac.contains("这是一段正常的话"));
        assertEquals(List.of("敏感词"), words(ac.match("含有敏感词")));
    }

    @Test
    @DisplayName("干扰符：敏*感-词、敏 感 词 这类写法同样命中")
    void stopCharsAreSkipped() {
        AcAutomaton ac = AcAutomaton.build(List.of("敏感词"));

        assertTrue(ac.contains("这是敏*感-词"), "插入符号不应成为规避手段");
        assertTrue(ac.contains("这是敏 感 词"), "插入空格不应成为规避手段");
        assertTrue(ac.contains("这是敏_感~词"));
    }

    @Test
    @DisplayName("全角与大小写：ＭＬＧＢ 与 MLGB 都能命中词库里的 mlgb")
    void fullWidthAndCaseNormalized() {
        AcAutomaton ac = AcAutomaton.build(List.of("mlgb"));

        assertTrue(ac.contains("他在说 ＭＬＧＢ"), "全角应转半角");
        assertTrue(ac.contains("他在说 MLGB"), "大写应转小写");
    }

    @Test
    @DisplayName("干扰符不含字母数字：不会把正常英文撕碎造成误命中")
    void digitsAreNotSkipped() {
        AcAutomaton ac = AcAutomaton.build(List.of("abc"));

        assertFalse(ac.contains("a1b2c3"),
                "数字不能被当干扰符跳过——跳过的话任何英文都会被撕碎、误命中率极高");
        assertFalse(ac.contains("axbxc"));
    }

    @Test
    @DisplayName("重叠词：abc 与 bcd 在 abcd 中都要命中")
    void overlappingWordsBothHit() {
        AcAutomaton ac = AcAutomaton.build(List.of("abc", "bcd"));

        List<AcAutomaton.Hit> hits = ac.match("abcd");
        assertEquals(2, hits.size(), "重叠的两个词都应命中");
        assertTrue(words(hits).containsAll(List.of("abc", "bcd")));
        // abc 占 [0,3)，bcd 占 [1,4)
        assertEquals(0, hits.stream().filter(h -> h.word().equals("abc")).findFirst().orElseThrow().start());
        assertEquals(1, hits.stream().filter(h -> h.word().equals("bcd")).findFirst().orElseThrow().start());
    }

    @Test
    @DisplayName("前缀词：ab 与 abc 在 abc 中都要命中（靠构建时合并 fail 链上的 output）")
    void prefixWordsBothHit() {
        AcAutomaton ac = AcAutomaton.build(List.of("ab", "abc"));

        List<AcAutomaton.Hit> hits = ac.match("abc");
        assertEquals(2, hits.size(),
                "短词是长词的前缀时也应命中；少了这一步说明 output 没有沿 fail 链合并");
        assertTrue(words(hits).containsAll(List.of("ab", "abc")));
    }

    @Test
    @DisplayName("命中区间映射回原文：截取出来的子串确实覆盖整个命中（含干扰符）")
    void hitRangeMapsBackToOriginalText() {
        AcAutomaton ac = AcAutomaton.build(List.of("敏感词"));
        String text = "前缀敏*感*词后缀";

        List<AcAutomaton.Hit> hits = ac.match(text);
        assertEquals(1, hits.size(), "带干扰符时应只命中一次");

        AcAutomaton.Hit hit = hits.get(0);
        // 返回的是原文索引：截取得到的是「含干扰符」的原文片段，而不是归一化后的串
        assertEquals("敏*感*词", text.substring(hit.start(), hit.end()));
        assertEquals("敏感词", hit.word(), "对外报告的是词库里的原始写法");
    }

    @Test
    @DisplayName("空词库 / 空文本 / null：都不抛异常，且一律无命中")
    void emptyInputsNeverThrow() {
        AcAutomaton empty = AcAutomaton.build(List.of());

        assertFalse(empty.contains("任意文本"));
        assertTrue(empty.match("任意文本").isEmpty());
        assertTrue(empty.match("").isEmpty());
        assertTrue(empty.match(null).isEmpty());

        AcAutomaton ac = AcAutomaton.build(List.of("敏感词"));
        assertTrue(ac.match("").isEmpty());
        assertTrue(ac.match(null).isEmpty());
    }

    @Test
    @DisplayName("词库里的空串与空白词被忽略：绝不会「匹配一切」")
    void blankWordsAreIgnored() {
        // 空模式在每个字符位置都会命中，是这类实现最隐蔽的坑：
        // 一行空行、一个手滑的逗号分隔，就会让所有内容被判为违规
        AcAutomaton ac = AcAutomaton.build(Arrays.asList("", "   ", "敏感词"));

        assertFalse(ac.contains("完全正常的文本"), "空词不应命中任何内容");
        assertTrue(ac.contains("这里有敏感词"));
    }

    @Test
    @DisplayName("自定义干扰符集合：传入 null 表示不跳过任何字符")
    void customStopChars() {
        AcAutomaton strict = AcAutomaton.build(List.of("敏感词"), null);
        assertFalse(strict.contains("这是敏*感*词"), "不跳过干扰符时，带符号的写法不应命中");

        AcAutomaton withStop = AcAutomaton.build(List.of("敏感词"), Set.of('*'));
        assertTrue(withStop.contains("这是敏*感*词"));
    }
}

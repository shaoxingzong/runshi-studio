package com.bhu.runshistudioweb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项标签工具验收测试（纯单测：不启 Spring、不连数据库）
 *
 * @author shaoshing
 */
class QuizLabelUtilsTest {

    // normalize

    @Test
    @DisplayName("规范化：大写 + 去重 + 升序 + 去分隔符")
    void normalizeVariants() {
        assertEquals("AC", QuizLabelUtils.normalize("AC"));
        assertEquals("AC", QuizLabelUtils.normalize("ac"), "小写必须被统一，否则选对了也判错");
        assertEquals("AC", QuizLabelUtils.normalize("CA"), "顺序不同是同一个集合，不能算错");
        assertEquals("AC", QuizLabelUtils.normalize("C,A"), "逗号分隔是最常见的输入形态");
        assertEquals("AC", QuizLabelUtils.normalize(" A C "), "空白要忽略");
        assertEquals("AC", QuizLabelUtils.normalize("c、a"), "全角顿号也要认");
        assertEquals("ABD", QuizLabelUtils.normalize("d,B,a"));
    }

    @Test
    @DisplayName("规范化：去重（AA 与 A 是同一个选择）")
    void normalizeDeduplicates() {
        assertEquals("A", QuizLabelUtils.normalize("AA"));
        assertEquals("AC", QuizLabelUtils.normalize("AACC"));
    }

    @Test
    @DisplayName("规范化：非法字符被静默丢弃（合法性由 findInvalidLabel 判定）")
    void normalizeDropsInvalidCharacters() {
        assertEquals("AC", QuizLabelUtils.normalize("AZC"));
        assertEquals("", QuizLabelUtils.normalize("XYZ"));
        assertEquals("", QuizLabelUtils.normalize("123"));
    }

    @Test
    @DisplayName("规范化：空输入返回空串（不返回 null，避免调用方 NPE）")
    void normalizeEmpty() {
        assertEquals("", QuizLabelUtils.normalize(null));
        assertEquals("", QuizLabelUtils.normalize(""));
        assertEquals("", QuizLabelUtils.normalize("   "));
        assertEquals("", QuizLabelUtils.normalize(",，、"));
    }

    // findInvalidLabel

    @Test
    @DisplayName("非法字符检测：超出 A–F 的字符")
    void findInvalidOutOfRange() {
        assertEquals('Z', QuizLabelUtils.findInvalidLabel("AZ"));
        assertEquals('1', QuizLabelUtils.findInvalidLabel("A1"));
        assertEquals('G', QuizLabelUtils.findInvalidLabel("G"));
        assertEquals('Z', QuizLabelUtils.findInvalidLabel("az"));
    }

    @Test
    @DisplayName("非法字符检测：重复标签要被拦下（否则会被静默折叠成单个）")
    void findInvalidDuplicate() {
        assertEquals('A', QuizLabelUtils.findInvalidLabel("AA"));
        assertEquals('C', QuizLabelUtils.findInvalidLabel("ACC"));
    }

    @Test
    @DisplayName("非法字符检测：分隔符与空白不算非法")
    void findInvalidAllowsSeparators() {
        assertNull(QuizLabelUtils.findInvalidLabel("A,C"));
        assertNull(QuizLabelUtils.findInvalidLabel("A、C"));
        assertNull(QuizLabelUtils.findInvalidLabel(" A C "));
        assertNull(QuizLabelUtils.findInvalidLabel("ACD"));
        assertNull(QuizLabelUtils.findInvalidLabel(null));
        assertNull(QuizLabelUtils.findInvalidLabel(""));
    }

    // 转换

    @Test
    @DisplayName("转列表：升序、保持顺序")
    void toListAndLabelList() {
        assertEquals(List.of('A', 'C', 'D'), QuizLabelUtils.toList("ACD"));
        assertEquals(List.of("A", "C", "D"), QuizLabelUtils.toLabelList("ACD"));
        assertTrue(QuizLabelUtils.toList(null).isEmpty());
        assertTrue(QuizLabelUtils.toLabelList("").isEmpty());
    }

    @Test
    @DisplayName("连续标签生成：A、B、C……")
    void sequentialLabels() {
        assertEquals(List.of("A"), QuizLabelUtils.sequentialLabels(1));
        assertEquals(List.of("A", "B", "C", "D"), QuizLabelUtils.sequentialLabels(4));
        assertEquals(6, QuizLabelUtils.sequentialLabels(6).size());
        assertTrue(QuizLabelUtils.sequentialLabels(0).isEmpty());
        assertTrue(QuizLabelUtils.sequentialLabels(-1).isEmpty());
    }
}

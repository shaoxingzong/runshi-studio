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
 * 本类最关键的一条是「大小写与顺序不影响判分」：
 * 用户点的是选项、传上来的却是字符串，"AC" / "ca" / "C,A"
 * 都是同一个选择。如果规范化没做对，选对了也会被判错，
 * 而且这种错误极难复现——用户说他选的是 A 和 C，日志里存的是 "C,A"。
 */
class QuizLabelUtilsTest {

    // ==================== normalize ====================

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
        // 这里只负责「整理」，不负责「报错」：调用方要先调 findInvalidLabel
        // 拿到非法字符，才能给出「Z 不是有效选项」这种精确提示
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

    // ==================== findInvalidLabel ====================

    @Test
    @DisplayName("非法字符检测：超出 A–F 的字符")
    void findInvalidOutOfRange() {
        assertEquals('Z', QuizLabelUtils.findInvalidLabel("AZ"));
        assertEquals('1', QuizLabelUtils.findInvalidLabel("A1"));
        assertEquals('G', QuizLabelUtils.findInvalidLabel("G"));
        // 小写输入会先被转成大写再判定，因此返回的是大写字母——
        // 这是刻意的：提示文案里给 "Z" 比给 "z" 更符合「选项标签都是大写」的直觉
        assertEquals('Z', QuizLabelUtils.findInvalidLabel("az"));
    }

    @Test
    @DisplayName("非法字符检测：重复标签要被拦下（否则会被静默折叠成单个）")
    void findInvalidDuplicate() {
        // "AA" 会被 normalize 折叠成 "A"，用户以为选了两个、实际只选了一个——
        // 这种「输入被悄悄改写」比直接报错更难排查
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

    // ==================== 转换 ====================

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

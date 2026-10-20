package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量导入结果（管理端）
 *
 * author: shaoshing
 *
 * <p><b>{@code dryRun} 与真实导入共用本 VO</b>：前者只填 {@code parsed} 与 {@code errors}，
 * {@code imported} 恒为 0。前端因此可以用同一套渲染逻辑做「预览」与「结果」。
 *
 * <p>{@code errors} 里带<b>行号</b>是刻意的：粘贴一大段文本时，
 * 「第 12 行多选题只标了 1 个正确项」能让人立刻定位；
 * 只说「有 3 个错误」等于没说。
 */
@Data
public class QuizImportResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 解析成功的题目数 */
    private Integer parsed;

    /** 实际入库的题目数（dryRun 时为 0） */
    private Integer imported;

    /** 是否只是校验（未落库） */
    private Boolean dryRun;

    /** 错误列表；为空表示整批合法 */
    private List<ImportError> errors;

    /**
     * 单条错误：行号 + 可读原因
     *
     * <p>做成静态内部类而不是独立 VO：它只在本 VO 里出现，
     * 单独一个文件会让 {@code model/vo/quiz} 下多出一个永远只有一处引用的类。
     */
    @Data
    public static class ImportError implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 出错的行号（1 起，对应粘贴文本里的物理行） */
        private Integer line;

        /** 可读原因（直接展示给管理员） */
        private String message;

        public ImportError() {
        }

        public ImportError(Integer line, String message) {
            this.line = line;
            this.message = message;
        }
    }
}

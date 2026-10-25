package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量导入结果（管理端）
 *
 * dryRun 与真实导入共用本 VO：前者只填 parsed 与 errors，
 * imported 恒为 0。前端因此可以用同一套渲染逻辑做「预览」与「结果」。
 *
 * errors 里带行号是刻意的：粘贴一大段文本时，
 * 「第 12 行多选题只标了 1 个正确项」能让人立刻定位；
 * 只说「有 3 个错误」等于没说。
 *
 * @author shaoshing
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
     * 做成静态内部类而不是独立 VO：它只在本 VO 里出现，
     * 单独一个文件会让 model/vo/quiz 下多出一个永远只有一处引用的类。
     */
    @Data
    public static class ImportError implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 出错的行号（1 起，对应粘贴文本里的物理行） */
        private Integer line;

        /** 可读原因（直接展示给管理员） */
        private String message;

        /**
         * 供 Jackson 反序列化使用
         */
        public ImportError() {
        }

        /**
         * 构造一条导入错误明细
         *
         * @param line 出错行号（从 1 开始）
         * @param message 失败原因
         */
        public ImportError(Integer line, String message) {
            this.line = line;
            this.message = message;
        }
    }
}

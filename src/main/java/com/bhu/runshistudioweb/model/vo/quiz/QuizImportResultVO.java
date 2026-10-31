package com.bhu.runshistudioweb.model.vo.quiz;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量导入结果（管理端）
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

    /** 单条错误：行号 + 可读原因 */
    @Data
    public static class ImportError implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 出错的行号（1 起，对应粘贴文本里的物理行） */
        private Integer line;

        /** 可读原因（直接展示给管理员） */
        private String message;

        /** 供 Jackson 反序列化使用 */
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

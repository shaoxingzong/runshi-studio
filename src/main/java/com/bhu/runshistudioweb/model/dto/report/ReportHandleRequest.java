package com.bhu.runshistudioweb.model.dto.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员处置举报的请求
 *
 * 三个动作的语义刻意分开，不做「一个布尔表示通过/不通过」：
 *   resolve —— 举报成立，内容撤下（转「已驳回」，保留可追溯）
 *   delete  —— 举报成立，内容直接删除（不可恢复）
 *   dismiss —— 举报不成立，内容保留
 * 「撤下」与「删除」对作者的影响完全不同，合成一个动作会让管理员无从选择。
 *
 * @author shaoshing
 */
@Data
public class ReportHandleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报记录 ID */
    @NotNull(message = "举报 id 不能为空")
    @Positive(message = "举报 id 不合法")
    private Long id;

    /** 处置动作：resolve / delete / dismiss（大小写敏感，与前端约定一致） */
    @NotBlank(message = "处置动作不能为空")
    private String action;

    /**
     * 处置说明（内部复盘用，不返回给举报人）
     *
     * 不填时由服务端按动作生成默认文案；上限 256 与 DDL 列宽一致。
     */
    @Size(max = 256, message = "处置说明不能超过 256 个字符")
    private String handleResult;
}

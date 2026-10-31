package com.bhu.runshistudioweb.model.dto.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户提交举报的请求
 *
 * @author shaoshing
 */
@Data
public class ReportAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报目标类型：post-帖子 / comment-评论，取值见 ReportTargetTypeEnum */
    @NotBlank(message = "举报对象类型不能为空")
    private String targetType;

    /** 举报目标 ID（对应帖子或评论的主键），必须为正数 */
    @NotNull(message = "举报对象不能为空")
    @Positive(message = "举报对象 ID 不合法")
    private Long targetId;

    /** 举报理由（给管理员看，不对外公开） */
    @NotBlank(message = "请填写举报理由")
    @Size(max = 256, message = "举报理由不能超过 256 个字符")
    private String reason;
}

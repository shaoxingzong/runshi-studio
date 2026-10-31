package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 审核请求（帖子与评论共用）
 *
 * @author shaoshing
 */
@Data
public class AuditRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待审核的 ID 列表（必填，至少一个） */
    @NotEmpty(message = "请选择要审核的内容")
    private List<Long> ids;

    /** 审核动作（必填）：approve-通过，reject-驳回 */
    @NotBlank(message = "审核动作不能为空")
    private String action;

    /** 驳回理由（驳回时必填，≤256） */
    @Size(max = 256, message = "驳回理由不能超过 256 个字符")
    private String rejectReason;
}

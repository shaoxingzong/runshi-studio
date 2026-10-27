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
 * 帖子与评论的审核动作完全同构：选一批 id、决定通过还是驳回、驳回时填理由，故共用一个 DTO。
 * 支持批量是因为管理员面对的是待审队列，勾选多条一次通过能直接压低人工成本。
 *
 * action 用字符串而不是枚举：枚举一加取值注解就过期，合法性由 Service 校验并把允许的取值写进提示。
 *
 * @author shaoshing
 */
@Data
public class AuditRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待审核的 ID 列表（必填，至少一个） */
    @NotEmpty(message = "请选择要审核的内容")
    private List<Long> ids;

    /** 审核动作（必填）： approve-通过， reject-驳回 */
    @NotBlank(message = "审核动作不能为空")
    private String action;

    /**
     * 驳回理由（驳回时必填，≤256）
     *
     * 它会展示给作者看，要写成「哪里不合适、怎么改」，而不是内部审核术语。通过时忽略该字段。
     */
    @Size(max = 256, message = "驳回理由不能超过 256 个字符")
    private String rejectReason;
}

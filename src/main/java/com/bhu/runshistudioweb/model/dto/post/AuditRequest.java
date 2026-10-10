package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 审核请求（<b>帖子与评论共用</b>）
 *
 * author: shaoshing
 *
 * <p>为什么帖子和评论共用一个 DTO：两者的审核动作完全同构——
 * 选一批 id、决定通过还是驳回、驳回时填理由。分开建两个内容一样的类没有意义。
 *
 * <p><b>支持批量</b>（{@code ids} 是数组）是减负的关键：
 * 管理员日常面对的是待审队列，逐条点开审核会把时间耗在页面切换上；
 * 勾选多条一次通过，是把人工成本压下来的直接手段。
 *
 * <p><b>{@code action} 用字符串而不是枚举</b>：它是接口契约的一部分，
 * 枚举一加取值注解就过期；合法性由 Service 校验，
 * 并把允许的取值写在提示里（与项目其它模块的枚举校验方式一致）。
 */
@Data
public class AuditRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待审核的 ID 列表（必填，至少一个）
     */
    @NotEmpty(message = "请选择要审核的内容")
    private List<Long> ids;

    /**
     * 审核动作（必填）：{@code approve}-通过，{@code reject}-驳回
     */
    @NotBlank(message = "审核动作不能为空")
    private String action;

    /**
     * 驳回理由（驳回时必填，≤256）
     *
     * <p>它<b>会展示给作者看</b>，所以要写成「哪里不合适、怎么改」，
     * 而不是内部的审核术语。通过时忽略该字段。
     */
    @Size(max = 256, message = "驳回理由不能超过 256 个字符")
    private String rejectReason;
}

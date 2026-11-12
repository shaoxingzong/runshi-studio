package com.bhu.runshistudioweb.model.dto.board;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 编辑内部事项请求（仅管理员）
 *
 * <p>字段为 null 表示不修改。负责人改派走 /assign，状态改走 /status，
 * 这样「null = 不修改」的语义不会被「清空指派」破坏。
 *
 * @author shaoshing
 */
@Data
public class BoardItemUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "事项 ID 不能为空")
    private Long id;

    @Size(max = 128, message = "事项标题不能超过 128 个字符")
    private String title;

    @Size(max = 4000, message = "事项说明不能超过 4000 个字符")
    private String description;

    /** 截止日期时间；null 表示不修改。要「取消截止日期」由前端传空串经 Service 约定的哨兵处理 */
    private LocalDateTime deadline;

    /** 是否清空截止日期：为 true 时忽略 deadline 并把列置为 null */
    private Boolean clearDeadline;

    /** 同列内展示权重；null 表示不修改 */
    private Integer sortOrder;
}

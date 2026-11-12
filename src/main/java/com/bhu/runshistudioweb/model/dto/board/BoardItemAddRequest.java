package com.bhu.runshistudioweb.model.dto.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 新增内部事项请求（仅管理员）
 *
 * @author shaoshing
 */
@Data
public class BoardItemAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事项标题 */
    @NotBlank(message = "事项标题不能为空")
    @Size(max = 128, message = "事项标题不能超过 128 个字符")
    private String title;

    /** 事项说明（Markdown） */
    @Size(max = 4000, message = "事项说明不能超过 4000 个字符")
    private String description;

    /** 初始状态，默认待办；取值见 BoardItemStatusEnum */
    private Integer status;

    /** 负责人成员档案 ID；不传或 null 表示未指派 */
    private Long assigneeMemberId;

    /** 截止日期时间（yyyy-MM-dd HH:mm:ss）；不传表示不设截止 */
    private LocalDateTime deadline;

    /** 同列内展示权重，默认 0 */
    private Integer sortOrder;

    /** 一次性幂等令牌：失败重试复用同一个值，成功后换新 */
    @Size(max = 64, message = "requestId 不能超过 64 个字符")
    private String requestId;
}

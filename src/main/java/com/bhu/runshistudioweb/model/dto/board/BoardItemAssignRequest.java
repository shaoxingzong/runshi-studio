package com.bhu.runshistudioweb.model.dto.board;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 指派负责人请求（仅管理员）
 *
 * @author shaoshing
 */
@Data
public class BoardItemAssignRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "事项 ID 不能为空")
    private Long id;

    /** 负责人成员档案 ID；null = 清空指派 */
    private Long assigneeMemberId;
}

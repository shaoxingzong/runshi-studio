package com.bhu.runshistudioweb.model.dto.board;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端事项查询请求（分页 + 筛选）
 *
 * @author shaoshing
 */
@Data
public class BoardItemQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题模糊匹配；上限 64，与前端搜索框 maxlength 对齐 */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String title;

    /** 状态精确匹配：0-待办, 1-进行中, 2-已完成 */
    private Integer status;

    /** 负责人成员档案 ID，精确匹配 */
    private Long assigneeMemberId;

    /** 只看逾期未完成项 */
    private Boolean onlyOverdue;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过 50 收敛到 50 */
    private Long pageSize = 10L;
}

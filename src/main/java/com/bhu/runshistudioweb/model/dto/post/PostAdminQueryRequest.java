package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：帖子全量列表查询请求
 *
 * @author shaoshing
 */
@Data
public class PostAdminQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审核状态（精确匹配，选填）：0-待审, 1-已通过, 2-已驳回 */
    private Integer status;

    /** 标题关键词（模糊查询，选填；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String titleKeyword;

    /** 作者关键词（模糊查询，选填） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String authorKeyword;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛到上限（防止一次拉全表） */
    private Long pageSize = 10L;
}

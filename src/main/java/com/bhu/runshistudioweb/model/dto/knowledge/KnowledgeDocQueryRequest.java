package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识库文档分页查询条件（管理端）
 *
 * 用 POST + 请求体：后台分页接口一律 POST + JSON，避免筛选条件拼进 URL 造成日志泄露与长度限制。
 *
 * @author shaoshing
 */
@Data
public class KnowledgeDocQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题关键词（模糊匹配，可选；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String title;

    /** 来源类型（精确匹配，可选）：manual / project / member / certificate */
    private String sourceType;

    /** 索引状态（精确匹配，可选）：0-待处理，1-已索引，2-失败 */
    private Integer status;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛到上限 */
    private Long pageSize = 10L;
}

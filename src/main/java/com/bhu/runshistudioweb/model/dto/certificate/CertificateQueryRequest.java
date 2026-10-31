package com.bhu.runshistudioweb.model.dto.certificate;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：分页查询证书请求
 *
 * @author shaoshing
 */
@Data
public class CertificateQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 id（精确匹配） */
    private Long id;

    /** 证书名称（模糊查询；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String title;

    /** 级别维度（精确匹配）：national / provincial / municipal 走 idx_level_date 的最左前缀 */
    private String awardLevel;

    /** 类型维度（精确匹配）：competition / soft_copyright / patent / paper 走 idx_type_date 的最左前缀 */
    private String awardType;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛（防止一次拉全表） */
    private Long pageSize = 10L;

    /** 排序字段：id / title / awardLevel / awardType / awardDate / sortOrder 只允许这几个值，Service 里用白名单映射成实体字段引用。 */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理 */
    private String sortOrder;
}

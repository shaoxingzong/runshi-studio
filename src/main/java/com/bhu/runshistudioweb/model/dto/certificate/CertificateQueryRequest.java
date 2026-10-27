package com.bhu.runshistudioweb.model.dto.certificate;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：分页查询证书请求
 *
 * 不传 sortField 时，Service 必须按 sort_order 倒序 + award_date 倒序排序（置顶优先、同权重按时间从新到旧），
 * 这个组合与 idx_type_date、idx_level_date 的索引结构同向，按维度筛选时能直接吃索引的有序性，避免 filesort。
 *
 * awardLevel、awardType 精确匹配，title 模糊匹配；字段为 null 表示该条件不参与筛选。
 * 若将来要「按获奖年份筛选」，加 awardYear（Integer）比加日期区间更符合官网用法，也更容易走索引。
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

    /**
     * 级别维度（精确匹配）：national / provincial / municipal
     * 走 idx_level_date 的最左前缀
     */
    private String awardLevel;

    /**
     * 类型维度（精确匹配）：competition / soft_copyright / patent / paper
     * 走 idx_type_date 的最左前缀
     */
    private String awardType;

    /** 页码，从 1 开始；不传按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传按 10 处理，超过上限会被收敛（防止一次拉全表） */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / title / awardLevel / awardType / awardDate / sortOrder
     * 只允许这几个值，Service 里用白名单映射成实体字段引用。ORDER BY 位置无法参数化，只能靠白名单防注入
     */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理 */
    private String sortOrder;
}

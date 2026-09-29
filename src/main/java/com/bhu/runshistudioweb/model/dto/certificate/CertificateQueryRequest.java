package com.bhu.runshistudioweb.model.dto.certificate;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：分页查询证书请求
 *
 * author: shaoshing
 *
 * <p>与成员模块的查询 DTO 保持同一套写法：**普通参数载体 + 自己声明分页字段**，
 * 由 Service 转成 MyBatis-Plus 的 {@code Page}。
 * 不要继承 Spring Data 的 {@code PageRequest}——它绑不上参数，且属于另一套分页模型。
 *
 * <p><b>排序约定（本模块的关键）</b>：不传 {@code sortField} 时，
 * Service 必须按 {@code sort_order 倒序 + award_date 倒序} 排序
 * （置顶优先、同权重按获奖时间从新到旧）。这个组合与
 * {@code idx_type_date (award_type, award_date DESC)}、
 * {@code idx_level_date (award_level, award_date DESC)} 的索引结构同向，
 * 按维度筛选时能直接吃索引的有序性，避免 filesort。
 *
 * <p>字段语义：{@code awardLevel}、{@code awardType} 为精确匹配，
 * {@code title} 为模糊匹配，字段为 null 表示「该条件不参与筛选」。
 *
 * <p>扩展提示：若将来需要「按获奖年份筛选」，加 {@code awardYear}（Integer）即可，
 * 比加 {@code awardDateStart/awardDateEnd} 区间更符合官网的使用方式，
 * 也更容易走索引（可用 {@code YEAR(award_date)} 的等价范围条件实现）。
 */
@Data
public class CertificateQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（精确匹配）
     */
    private Long id;

    /**
     * 证书名称（模糊查询）
     */
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

    /**
     * 页码，从 1 开始；不传按 1 处理
     */
    private Long current = 1L;

    /**
     * 每页条数；不传按 10 处理，超过上限会被收敛（防止一次拉全表）
     */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / title / awardLevel / awardType / awardDate / sortOrder
     * <p>只允许这几个值，Service 里用白名单映射成实体字段引用。
     * 绝不能把前端字符串直接拼进 SQL——ORDER BY 位置无法参数化，只能靠白名单
     */
    private String sortField;

    /**
     * 排序方向：asc / desc，不传或非法按 desc 处理
     */
    private String sortOrder;
}

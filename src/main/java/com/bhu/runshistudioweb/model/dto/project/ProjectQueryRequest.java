package com.bhu.runshistudioweb.model.dto.project;

import lombok.Data;

import java.io.Serializable;

/**
 * 项目查询请求（管理端分页列表与 C 端公开列表共用）
 *
 * author: shaoshing
 *
 * <p>与 {@code MemberQueryRequest} 完全同一套路：普通参数载体 + 自己声明分页字段，
 * 由 Service 转成 MyBatis-Plus 的 {@code Page}。
 *
 * <p><b>两端对参数的用法</b>：
 * <ul>
 *     <li>管理端 {@code /project/list/page}：使用 {@code current / pageSize / sortField / sortOrder}，
 *     排序字段走白名单映射（ORDER BY 无法参数化，只能靠白名单防注入）；</li>
 *     <li>C 端 {@code /project/list}：使用 {@code current / pageSize}，
 *     <b>忽略</b> {@code sortField / sortOrder}——公开接口的排序由产品定义
 *     （固定 sort_order DESC → created_at DESC → id DESC），不接受请求方指定。</li>
 * </ul>
 *
 * <p><b>刻意没有 techStack 筛选参数</b>：{@code tech_stack} 是 JSON 字符串列，
 * 按它筛选只能写 {@code LIKE '%Java%'}，既走不了索引又会误匹配
 * （搜 "Java" 命中 "JavaScript"）。DESIGN 3.3 的取舍是「不筛、不建索引」，
 * 将来若要按技术栈筛选，正确做法是拆标签表而不是加 LIKE 条件。
 */
@Data
public class ProjectQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目 id（精确匹配）
     */
    private Long id;

    /**
     * 项目名称（模糊匹配）
     */
    private String title;

    /**
     * 项目状态（精确匹配）：0-研发中，1-已上线，2-已结题
     * <p>走 idx_status_sort_time 的最左前缀；非法取值返回 A0401（闭集，不能静默返回空列表）
     */
    private Integer status;

    /**
     * 队长 ID（精确匹配）
     * <p>走 idx_leader
     */
    private Long leaderId;

    /**
     * 页码，从 1 开始；不传或 &lt; 1 按 1 处理
     */
    private Long current = 1L;

    /**
     * 每页条数；不传或 &lt; 1 按 10 处理，超过上限（50）会被收敛
     */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / title / status / sortOrder / createdAt（仅管理端使用）
     * <p>由 Service 用白名单映射成实体字段引用，绝不能直接拼进 SQL
     */
    private String sortField;

    /**
     * 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用）
     */
    private String sortOrder;
}

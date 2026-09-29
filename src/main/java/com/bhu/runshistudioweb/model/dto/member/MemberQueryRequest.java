package com.bhu.runshistudioweb.model.dto.member;

import lombok.Data;

import java.io.Serializable;

/**
 * 成员查询请求（管理端分页列表与 C 端公开列表共用）
 *
 * author: shaoshing
 *
 * <p>字段语义：
 * <ul>
 *     <li>{@code id / gradeYear / teamPosition / memberStatus} 为精确匹配——它们是枚举值域或数值，
 *     精确匹配才能命中索引、结果也可预期；</li>
 *     <li>{@code name / direction} 为模糊匹配——姓名与方向是自由文本，用户习惯用关键词搜；</li>
 *     <li>字段为 null 表示「该条件不参与筛选」，不是「查 null 值」。</li>
 * </ul>
 *
 * <p><b>管理端与 C 端对分页/排序参数的用法不同</b>（同一个 DTO 各取所需）：
 * <ul>
 *     <li>管理端 {@code /member/list/page}：使用 {@code current / pageSize / sortField / sortOrder}；
 *     非法值不在 DTO 报错，由 Service 兜底纠正（页码 &lt; 1 视为 1、pageSize 上限 50，
 *     排序字段走白名单映射）；</li>
 *     <li>C 端 {@code /member/list}：**忽略** {@code current / pageSize / sortField / sortOrder}，
 *     固定按置顶权重倒序返回——公开接口不接受任意排序参数，减少可被利用的输入面，
 *     排序规则由产品定义而不是由请求方决定。</li>
 * </ul>
 */
@Data
public class MemberQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员 id（精确匹配）
     */
    private Long id;

    /**
     * 成员姓名（模糊查询）
     */
    private String name;

    /**
     * 入学年份（精确匹配，如 2022）
     */
    private Integer gradeYear;

    /**
     * 技术方向（模糊查询）
     */
    private String direction;

    /**
     * 团队职务（精确匹配），取值见 {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}
     */
    private String teamPosition;

    /**
     * 成员状态（精确匹配）：0-在读/在队，1-毕业/离队
     */
    private Integer memberStatus;

    /**
     * 页码，从 1 开始；不传按 1 处理（仅管理端使用）
     */
    private Long current = 1L;

    /**
     * 每页条数；不传按 10 处理，超过上限会被收敛到上限（仅管理端使用）
     */
    private Long pageSize = 10L;

    /**
     * 排序字段：sortOrder / gradeYear / memberStatus / createdAt / id（仅管理端使用）
     * <p>只允许这几个值，Service 里用白名单映射成实体字段；
     * 绝不能把前端传来的字符串直接拼进 SQL——那是最典型的 SQL 注入入口
     */
    private String sortField;

    /**
     * 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用）
     */
    private String sortOrder;
}
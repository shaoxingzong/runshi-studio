package com.bhu.runshistudioweb.model.dto.member;

import lombok.Data;

import java.io.Serializable;

/**
 * 成员查询请求（管理端分页列表与 C 端公开列表共用）
 *
 * 字段语义：
 * - id / gradeYear / teamPosition / memberStatus 为精确匹配——它们是枚举值域或数值，
 * 精确匹配才能命中索引、结果也可预期；
 * - name / direction 为模糊匹配——姓名与方向是自由文本，用户习惯用关键词搜；
 * - 字段为 null 表示「该条件不参与筛选」，不是「查 null 值」。
 *
 * 管理端与 C 端对分页 / 排序参数的用法（同一个 DTO 各取所需）：
 * - 分页参数 current / pageSize：两端都使用（C 端自 起升级为真分页）。
 * 非法值不在 DTO 报错，由 Service 兜底纠正：页码 < 1 视为 1，pageSize < 1 视为 10、
 * > 50 收敛到 50（匿名接口必须防「一次拉全表」）；
 * - 排序参数 sortField / sortOrder：仅管理端使用，
 * 且由 Service 用白名单映射成实体字段；
 * - C 端 始终忽略排序参数，固定按 sort_order 倒序 + id 倒序返回——
 * 公开接口不接受任意排序字段：既减少可被利用的输入面，
 * 也因为「排序规则由产品定义」而不是由请求方决定。
 *
 * 为什么同一个 DTO 服务两端：C 端与管理端的筛选条件集合完全相同
 * （届别 / 方向 / 状态 / 姓名……），两端只在对分页与排序的处置上有差别；
 * 分成两个 DTO 只会带来「加一个筛选项要改两处」的维护成本。
 */
@Data
public class MemberQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 id（精确匹配） */
    private Long id;

    /** 成员姓名（模糊查询） */
    private String name;

    /** 入学年份（精确匹配，如 2022） */
    private Integer gradeYear;

    /** 技术方向（模糊查询） */
    private String direction;

    /** 团队职务（精确匹配），取值见 com.bhu.runshistudioweb.model.enums.TeamPositionEnum */
    private String teamPosition;

    /** 成员状态（精确匹配）：0-在读/在队，1-毕业/离队 */
    private Integer memberStatus;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理（管理端与 C 端都用） */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛到上限（管理端与 C 端都用） */
    private Long pageSize = 10L;

    /**
     * 排序字段：sortOrder / gradeYear / memberStatus / createdAt / id（仅管理端使用）
     * 只允许这几个值，Service 里用白名单映射成实体字段；
     * 绝不能把前端传来的字符串直接拼进 SQL——那是最典型的 SQL 注入入口
     */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用） */
    private String sortOrder;
}
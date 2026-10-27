package com.bhu.runshistudioweb.model.dto.member;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 成员查询请求（管理端分页列表与 C 端公开列表共用）
 *
 * id / gradeYear / teamPosition / memberStatus 精确匹配（枚举值域或数值，精确匹配才能命中索引），
 * name / direction 模糊匹配；字段为 null 表示该条件不参与筛选。
 *
 * 两端各取所需：分页参数都用，非法值由 Service 兜底纠正（页码 < 1 视为 1，pageSize 收敛到 50）；
 * 排序参数仅管理端使用并走白名单映射，C 端始终忽略，固定按 sort_order 倒序 + id 倒序——
 * 公开接口不接受任意排序字段，既减少可被利用的输入面，也因为排序规则由产品定义。
 *
 * 同一个 DTO 服务两端：筛选条件集合完全相同，分成两个只会带来「加一个筛选项要改两处」的成本。
 *
 * @author shaoshing
 */
@Data
public class MemberQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 id（精确匹配） */
    private Long id;

    /** 成员姓名（模糊查询；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String name;

    /** 入学年份（精确匹配，如 2022） */
    private Integer gradeYear;

    /** 技术方向（模糊查询；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
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
     * 只允许这几个值，Service 里用白名单映射成实体字段，绝不能把前端字符串直接拼进 SQL
     */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用） */
    private String sortOrder;
}
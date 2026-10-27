package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 项目查询请求（管理端分页列表与 C 端公开列表共用）
 *
 * 管理端用 current / pageSize / sortField / sortOrder，排序字段走白名单映射
 * （ORDER BY 无法参数化，只能靠白名单防注入）；C 端只用分页，忽略排序参数——
 * 公开接口的排序由产品定义（sort_order DESC → created_at DESC → id DESC）。
 *
 * 刻意没有 techStack 筛选：tech_stack 是 JSON 字符串列，按它筛选只能 LIKE '%Java%'，
 * 既走不了索引又会误匹配（搜 Java 命中 JavaScript）。真要按技术栈筛选应拆标签表。
 *
 * @author shaoshing
 */
@Data
public class ProjectQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目 id（精确匹配） */
    private Long id;

    /** 项目名称（模糊匹配；上限 64，与前端搜索框 maxlength 对齐） */
    @Size(max = 64, message = "搜索关键词不能超过 64 个字符")
    private String title;

    /**
     * 项目状态（精确匹配）：0-研发中，1-已上线，2-已结题
     * 走 idx_status_sort_time 的最左前缀；非法取值返回 A0401（闭集，不能静默返回空列表）
     */
    private Integer status;

    /**
     * 队长 ID（精确匹配）
     * 走 idx_leader
     */
    private Long leaderId;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛 */
    private Long pageSize = 10L;

    /**
     * 排序字段：id / title / status / sortOrder / createdAt（仅管理端使用）
     * 由 Service 用白名单映射成实体字段引用，绝不能直接拼进 SQL
     */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用） */
    private String sortOrder;
}

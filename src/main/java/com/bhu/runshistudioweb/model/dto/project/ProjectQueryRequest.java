package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 项目查询请求（管理端分页列表与 C 端公开列表共用）
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

    /** 项目状态（精确匹配）：0-研发中，1-已上线，2-已结题 走 idx_status_sort_time 的最左前缀； */
    private Integer status;

    /** 队长 ID（精确匹配）走 idx_leader */
    private Long leaderId;

    /** 页码，从 1 开始；不传或 < 1 按 1 处理 */
    private Long current = 1L;

    /** 每页条数；不传或 < 1 按 10 处理，超过上限（50）会被收敛 */
    private Long pageSize = 10L;

    /** 排序字段：id / title / status / sortOrder / createdAt（仅管理端使用）由 Service 用白名单映射成实体字段引用，绝不能直接拼进 SQL */
    private String sortField;

    /** 排序方向：asc / desc，不传或非法按 desc 处理（仅管理端使用） */
    private String sortOrder;
}

package com.bhu.runshistudioweb.model.vo.project;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目视图对象（管理端列表 / 详情使用）
 *
 * @author shaoshing
 */
@Data
public class ProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 项目名称 */
    private String title;

    /** 项目封面 URL */
    private String coverImage;

    /** 项目摘要 */
    private String description;

    /** 项目详情正文（Markdown，RAG 数据源）管理端必须能读到它才能编辑；C 端列表 VO 结构上就没有这个字段 */
    private String content;

    /** 技术栈标签（由 JSON 快照解析而来；解析失败时为空列表） */
    private List<String> techStack;

    /** 在线体验地址 */
    private String demoUrl;

    /** 开源仓库地址 */
    private String githubUrl;

    /** 项目队长 ID（权威数据源，19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long leaderId;

    /** 项目状态：0-研发中，1-已上线，2-已结题。取值见 com.bhu.runshistudioweb.model.enums.ProjectStatusEnum */
    private Integer status;

    /** 展示置顶权重（管理端必须能看到，否则无法核对官网排序） */
    private Integer sortOrder;

    /** 首页轮播排名（仅管理端列表返回，1-6 表示排官网首页第 N 位；null 表示不进首页） */
    private Integer homeRank;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

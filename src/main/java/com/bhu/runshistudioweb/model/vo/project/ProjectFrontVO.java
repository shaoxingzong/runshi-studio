package com.bhu.runshistudioweb.model.vo.project;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 项目视图对象（C 端列表使用，游客可获取）
 *
 * 脱敏靠「目标 VO 没有这些字段」而不是拷完再手动置空：刻意不含 content（text 大字段，
 * 列表每行都带上它响应体积会膨胀几十倍，只有详情 VO 才取）、leaderId（内部关联字段）、
 * sortOrder（运营置顶权重）与 createdAt / updatedAt / createdBy / updatedBy / deletedAt（审计字段）。
 * BeanUtils.copyProperties 只拷贝「两边都存在且类型一致」的属性，以后实体新增敏感字段时，
 * 只要不加进本类就自动被挡住。
 *
 * 保留 status 是因为官网要按状态渲染标签（研发中 / 已上线 / 已结题），保留 techStack 是因为卡片上
 * 要展示技术栈标签——它们都属于「愿意给所有人看」的信息。
 *
 * @author shaoshing
 */
@Data
public class ProjectFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 项目名称 */
    private String title;

    /** 项目封面 URL（官网卡片直接展示的图） */
    private String coverImage;

    /** 项目摘要（卡片上的短描述） */
    private String description;

    /** 技术栈标签（由 JSON 快照解析而来；解析失败时为空列表） */
    private List<String> techStack;

    /** 在线体验地址 */
    private String demoUrl;

    /** 开源仓库地址 */
    private String githubUrl;

    /** 项目状态：0-研发中，1-已上线，2-已结题 */
    private Integer status;
}

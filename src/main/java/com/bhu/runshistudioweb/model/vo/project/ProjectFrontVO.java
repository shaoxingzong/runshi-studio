package com.bhu.runshistudioweb.model.vo.project;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 项目视图对象（C 端列表使用，游客可获取）
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

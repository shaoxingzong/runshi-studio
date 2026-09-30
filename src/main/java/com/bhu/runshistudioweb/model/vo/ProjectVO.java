package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目视图对象（管理端列表 / 详情使用）
 *
 * author: shaoshing
 *
 * <p>与 C 端两个 VO 的分工（和成员、证书模块完全同一套路）：
 * <ul>
 *     <li>{@code ProjectVO}：给<b>管理员</b>看，字段齐全——含 {@code content} 正文、
 *     {@code leaderId}、{@code sortOrder} 与审计时间，后台编辑页面需要这些；</li>
 *     <li>{@link ProjectFrontVO}：给<b>游客</b>看的列表项，<b>不含</b> content 大字段，
 *     也不含 leaderId / sortOrder / 审计字段；</li>
 *     <li>{@link ProjectFrontDetailVO}：给游客看的详情，= 列表字段 + content。</li>
 * </ul>
 *
 * <p><b>{@code techStack} 在本 VO 里是 {@code List<String>}</b>：
 * 数据库存的是 JSON 快照字符串，接口层统一用列表（存储形态不外泄），
 * 转换在 Service 里做，解析失败（脏数据）时返回空列表而不是抛异常。
 *
 * <p>{@code id} 与 {@code leaderId} 显式标注 Jackson 3 的 {@code ToStringSerializer}：
 * 雪花 ID 是 19 位、超出 JS Number 安全整数范围，序列化为字符串避免前端精度丢失
 * （全局 JsonConfig 已统一处理，这里是字段级兜底）。
 */
@Data
public class ProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（雪花算法 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 项目名称
     */
    private String title;

    /**
     * 项目封面 URL
     */
    private String coverImage;

    /**
     * 项目摘要
     */
    private String description;

    /**
     * 项目详情正文（Markdown，RAG 数据源）
     * <p>管理端必须能读到它才能编辑；C 端列表 VO 结构上就没有这个字段
     */
    private String content;

    /**
     * 技术栈标签（由 JSON 快照解析而来；解析失败时为空列表）
     */
    private List<String> techStack;

    /**
     * 在线体验地址
     */
    private String demoUrl;

    /**
     * 开源仓库地址
     */
    private String githubUrl;

    /**
     * 项目队长 ID（权威数据源，19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long leaderId;

    /**
     * 项目状态：0-研发中，1-已上线，2-已结题
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.ProjectStatusEnum}
     */
    private Integer status;

    /**
     * 展示置顶权重（管理端必须能看到，否则无法核对官网排序）
     */
    private Integer sortOrder;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}

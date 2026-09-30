package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.util.List;

/**
 * 项目视图对象（<b>C 端列表</b>使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p><b>脱敏套路与其它模块一致：靠「目标 VO 没有这些字段」实现，而不是拷完再手动置空。</b>
 * 本类刻意不包含：
 * <ul>
 *     <li><b>{@code content}</b>——text 大字段（Markdown 正文）。
 *     列表查询若带上它，每一行都要把整篇正文传回前端，响应体积会膨胀几十倍；
 *     只有详情接口（{@link ProjectFrontDetailVO}）才取；</li>
 *     <li><b>{@code leaderId}</b>——内部关联字段，游客无需知道它指向哪个成员档案；</li>
 *     <li><b>{@code sortOrder}</b>——运营置顶权重，属于后台配置，不该被前台感知；</li>
 *     <li><b>{@code createdAt / updatedAt / createdBy / updatedBy / deletedAt}</b>——审计字段。</li>
 * </ul>
 * 由于 {@code BeanUtils.copyProperties} 只拷贝「两边都存在且类型一致」的属性，
 * 这些字段<b>结构上不可能</b>被带出去。以后实体新增敏感字段时，只要不加进本类就自动被挡住。
 *
 * <p>保留 {@code status} 是因为官网要按状态渲染标签（研发中 / 已上线 / 已结题），
 * 保留 {@code techStack} 是因为卡片上要展示技术栈标签——它们都属于「愿意给所有人看」的信息。
 */
@Data
public class ProjectFrontVO implements Serializable {

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
     * 项目封面 URL（官网卡片直接展示的图）
     */
    private String coverImage;

    /**
     * 项目摘要（卡片上的短描述）
     */
    private String description;

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
     * 项目状态：0-研发中，1-已上线，2-已结题
     */
    private Integer status;
}

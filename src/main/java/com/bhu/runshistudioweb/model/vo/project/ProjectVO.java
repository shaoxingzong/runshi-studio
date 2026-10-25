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
 * 与 C 端两个 VO 的分工（和成员、证书模块完全同一套路）：
 * - ProjectVO：给管理员看，字段齐全——含 content 正文、
 * leaderId、sortOrder 与审计时间，后台编辑页面需要这些；
 * - ProjectFrontVO：给游客看的列表项，不含 content 大字段，
 * 也不含 leaderId / sortOrder / 审计字段；
 * - ProjectFrontDetailVO：给游客看的详情，= 列表字段 + content。
 *
 * techStack 在本 VO 里是 List<String>：
 * 数据库存的是 JSON 快照字符串，接口层统一用列表（存储形态不外泄），
 * 转换在 Service 里做，解析失败（脏数据）时返回空列表而不是抛异常。
 *
 * id 与 leaderId 显式标注 Jackson 3 的 ToStringSerializer：
 * 雪花 ID 是 19 位、超出 JS Number 安全整数范围，序列化为字符串避免前端精度丢失
 * （全局 JsonConfig 已统一处理，这里是字段级兜底）。
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

    /**
     * 项目详情正文（Markdown，RAG 数据源）
     * 管理端必须能读到它才能编辑；C 端列表 VO 结构上就没有这个字段
     */
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

    /**
     * 项目状态：0-研发中，1-已上线，2-已结题
     * 取值见 com.bhu.runshistudioweb.model.enums.ProjectStatusEnum
     */
    private Integer status;

    /** 展示置顶权重（管理端必须能看到，否则无法核对官网排序） */
    private Integer sortOrder;

    /**
     * 首页轮播排名（仅管理端列表返回，1-6 表示排官网首页第 N 位；null 表示不进首页）
     *
     * 首页轮播取的是「官网口径前 6 条」，而官网口径是
     * sort_order DESC → created_at DESC → id DESC。运营在后台要能一眼看出
     * 「这条排第几、能不能进首页」——否则只能凭权重数字猜，而猜错的代价是
     * 「后台改了权重，首页却没动静」这类难排查的困惑。
     *
     * 刻意不放进 ProjectFrontVO：排名是运营视角的信息，
     * 对外暴露它等于告诉访客「我们是怎么排首页的」。
     */
    private Integer homeRank;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

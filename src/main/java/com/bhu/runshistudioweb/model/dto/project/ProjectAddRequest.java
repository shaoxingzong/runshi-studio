package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端：新增项目请求
 *
 * author: shaoshing
 *
 * <p>三个必填项的理由都来自 DDL：
 * <ul>
 *     <li>{@code title / description} 是 NOT NULL 列，不校验会直接抛 SQL 错误；</li>
 *     <li>{@code leaderId} 是 NOT NULL，而且它是「队长」的<b>权威数据源</b>——
 *     新增时由 Service 在同一事务内同步进关联表，所以必填不只是为了入库，
 *     更是为了保证「项目一定有一个队长」。</li>
 * </ul>
 *
 * <p><b>{@code techStack} 用 {@code List<String>} 而不是字符串</b>：
 * 数据库里存的是 JSON 快照字符串，但那是存储形态，不该泄漏到接口契约上。
 * 转换与长度校验（varchar(256) 上限）由 Service 负责，超出会返回 A0401
 * 「技术栈标签过长或过多」——不校验的话，前端塞 20 个标签会直接撞数据库的 SQL 错误。
 *
 * <p>{@code status} 不做注解白名单（理由与其它模块一致）：枚举一加取值注解就过期，
 * 合法性由 Service 用 {@link com.bhu.runshistudioweb.model.enums.ProjectStatusEnum} 校验，
 * 提示文案用 {@code valuesText()} 生成；不传时按 DDL 默认值 1（已上线）处理。
 */
@Data
public class ProjectAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目名称（必填，≤128）
     */
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称不能超过 128 个字符")
    private String title;

    /**
     * 项目摘要（必填，≤512）：列表页展示的短描述
     */
    @NotBlank(message = "项目摘要不能为空")
    @Size(max = 512, message = "项目摘要不能超过 512 个字符")
    private String description;

    /**
     * 项目队长 ID（必填，权威数据源）
     * <p>必须是真实存在且未逻辑删除的成员，由 Service 校验（A0402）
     */
    @NotNull(message = "项目队长不能为空")
    @Positive(message = "项目队长 id 必须为正整数")
    private Long leaderId;

    /**
     * 项目封面 URL（选填）
     * <p>一般先调 {@code /file/upload} 拿到 {@code /uploads/...} 相对路径再填到这里
     */
    @Size(max = 512, message = "项目封面地址过长")
    private String coverImage;

    /**
     * 项目详情正文（选填，Markdown）：RAG 切分的数据源
     */
    private String content;

    /**
     * 技术栈标签（选填）：对外是列表，入库为 JSON 快照
     */
    private List<String> techStack;

    /**
     * 在线体验地址（选填）
     */
    @Size(max = 256, message = "在线体验地址过长")
    private String demoUrl;

    /**
     * 开源仓库地址（选填）
     */
    @Size(max = 256, message = "开源仓库地址过长")
    private String githubUrl;

    /**
     * 项目状态（选填）：0-研发中，1-已上线，2-已结题；不传按 1（已上线）处理
     */
    private Integer status;

    /**
     * 展示置顶权重（选填）：数值越大越靠前；不传按 0 处理
     */
    private Integer sortOrder;
}

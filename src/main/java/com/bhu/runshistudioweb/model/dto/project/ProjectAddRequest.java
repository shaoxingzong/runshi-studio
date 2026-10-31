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
 * @author shaoshing
 */
@Data
public class ProjectAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目名称（必填，≤128） */
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称不能超过 128 个字符")
    private String title;

    /** 项目摘要（必填，≤512）：列表页展示的短描述 */
    @NotBlank(message = "项目摘要不能为空")
    @Size(max = 512, message = "项目摘要不能超过 512 个字符")
    private String description;

    /** 项目队长 ID（必填，权威数据源）必须是真实存在且未逻辑删除的成员，由 Service 校验（A0402） */
    @NotNull(message = "项目队长不能为空")
    @Positive(message = "项目队长 id 必须为正整数")
    private Long leaderId;

    /** 项目封面 URL（选填）一般先调 /file/upload 拿到 /uploads/... 相对路径再填到这里 */
    @Size(max = 512, message = "项目封面地址过长")
    private String coverImage;

    /** 项目详情正文（选填，Markdown）：RAG 切分的数据源 */
    private String content;

    /** 技术栈标签（选填）：对外是列表，入库为 JSON 快照 */
    private List<String> techStack;

    /** 在线体验地址（选填） */
    @Size(max = 256, message = "在线体验地址过长")
    private String demoUrl;

    /** 开源仓库地址（选填） */
    @Size(max = 256, message = "开源仓库地址过长")
    private String githubUrl;

    /** 项目状态（选填）：0-研发中，1-已上线，2-已结题；不传按 1（已上线）处理 */
    private Integer status;

    /** 展示置顶权重（选填）：数值越大越靠前；不传按 0 处理 */
    private Integer sortOrder;
}

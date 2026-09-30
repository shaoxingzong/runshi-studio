package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端：更新项目请求（部分更新，字段为 null 表示不修改）
 *
 * author: shaoshing
 *
 * <p>与新增的三点差别：
 * <ol>
 *     <li><b>不复用新增的 {@code @NotBlank}</b>：更新是部分更新语义，
 *     这里刻意<b>不</b>加必填注解——「不传」表示不改，「传了空串」才由 Service 拦 40000。
 *     若照搬新增的注解，前端「只改封面」就必须回传全部必填字段；</li>
 *     <li><b>{@code leaderId} 传了即换队长</b>：Service 会在同一事务内把新队长
 *     同步进关联表（幂等 ensure），并且<b>不移除旧队长</b>——卸任 ≠ 退出项目；</li>
 *     <li><b>{@code techStack} 传了即整体替换快照</b>：它是快照而非关联表，
 *     没有「增量加一个标签」的语义，传什么就是什么（传空数组表示清空）。</li>
 * </ol>
 *
 * <p>更新字段是<b>服务端写死的白名单</b>（见 Service）：
 * 绝不能直接把 DTO 转成实体 updateById，否则前端能改 deletedAt、审计字段等内部数据。
 */
@Data
public class ProjectUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（必填）
     */
    @NotNull(message = "项目 id 不能为空")
    private Long id;

    /**
     * 项目名称（选填；传了就必须有实际内容，不允许清成空串）
     */
    @Size(max = 128, message = "项目名称不能超过 128 个字符")
    private String title;

    /**
     * 项目摘要（选填）
     */
    @Size(max = 512, message = "项目摘要不能超过 512 个字符")
    private String description;

    /**
     * 项目队长 ID（选填；传了即更换队长）
     */
    @Positive(message = "项目队长 id 必须为正整数")
    private Long leaderId;

    /**
     * 项目封面 URL（选填）
     */
    @Size(max = 512, message = "项目封面地址过长")
    private String coverImage;

    /**
     * 项目详情正文（选填，Markdown）
     */
    private String content;

    /**
     * 技术栈标签（选填；传了即整体替换快照）
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
     * 项目状态（选填）：0-研发中，1-已上线，2-已结题
     */
    private Integer status;

    /**
     * 展示置顶权重（选填）
     */
    private Integer sortOrder;
}

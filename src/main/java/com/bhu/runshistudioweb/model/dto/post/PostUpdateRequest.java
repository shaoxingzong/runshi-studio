package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：编辑帖子请求
 *
 * 编辑权只给管理员：「先审后发」意味着内容公开前已过审，若作者能自由修改已通过的正文，
 * 等于给审核开后门——改完的内容没人再看。
 *
 * 不接受 status：编辑已通过的帖子会自动重置为待审重新送审（见 PostService#updatePost），
 * 让前端传「改完还是已通过」等于把编辑变成绕过审核的通道。
 *
 * content 不做 @Size 校验（与 PostAddRequest 一致），超大上限由 Service 常量校验。
 * summary 与 coverImage 选填，为空时沿用新增时的兜底逻辑。
 *
 * @author shaoshing
 */
@Data
public class PostUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待编辑的帖子 ID（必填） */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 必须为正整数")
    private Long id;

    /** 帖子标题（必填，≤128） */
    @NotBlank(message = "帖子标题不能为空")
    @Size(max = 128, message = "帖子标题不能超过 128 个字符")
    private String title;

    /**
     * 正文（必填，Markdown；图片以内嵌形式写在其中）
     *
     * 图片必须先经 /file/upload 上传，拿到 /uploads/... 相对路径后再写进正文；
     * Service 会校验所有图片地址都是站内路径，与 PostAddRequest 同一条规则。
     */
    @NotBlank(message = "帖子正文不能为空")
    private String content;

    /** 摘要（选填，≤512）：为空时由 Service 用正文前若干字兜底 */
    @Size(max = 512, message = "帖子摘要不能超过 512 个字符")
    private String summary;

    /** 封面图 URL（选填，≤512）：为空时由 Service 取正文第一张图 */
    @Size(max = 512, message = "帖子封面地址过长")
    private String coverImage;
}

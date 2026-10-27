package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发布帖子请求
 *
 * content 不做 @Size 注解校验，上限由 Service 判断：正文是 text 列，真正要挡的是「几十 MB 的正文」，
 * 这类超大上限用 Service 常量校验更直观，也能在超限时给出具体数字。
 *
 * summary 与 coverImage 选填，有兜底生成逻辑：摘要为空取正文前若干字，封面为空取正文第一张图。
 *
 * 不接受 status：审核状态由服务端决定（先审后发，一律先落「待审」），
 * 让前端传「已通过」等于把审核功能架空。
 *
 * @author shaoshing
 */
@Data
public class PostAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子标题（必填，≤128） */
    @NotBlank(message = "帖子标题不能为空")
    @Size(max = 128, message = "帖子标题不能超过 128 个字符")
    private String title;

    /**
     * 正文（必填，Markdown；图片以内嵌形式写在其中）
     *
     * 图片必须先经 /file/upload 上传，拿到 /uploads/... 相对路径后再写进正文。
     * Service 会校验所有图片地址都是站内路径，防止外链图成为追踪像素或将来裂图。
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

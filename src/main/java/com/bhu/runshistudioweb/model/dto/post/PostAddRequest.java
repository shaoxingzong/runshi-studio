package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发布帖子请求
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

    /** 正文（必填，Markdown；图片以内嵌形式写在其中） */
    @NotBlank(message = "帖子正文不能为空")
    private String content;

    /** 摘要（选填，≤512）：为空时由 Service 用正文前若干字兜底 */
    @Size(max = 512, message = "帖子摘要不能超过 512 个字符")
    private String summary;

    /** 封面图 URL（选填，≤512）：为空时由 Service 取正文第一张图 */
    @Size(max = 512, message = "帖子封面地址过长")
    private String coverImage;
}

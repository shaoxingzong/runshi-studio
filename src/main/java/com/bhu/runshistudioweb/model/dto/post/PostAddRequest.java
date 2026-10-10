package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发布帖子请求
 *
 * author: shaoshing
 *
 * <p><b>{@code content} 不做 {@code @Size} 注解校验</b>，长度上限由 Service 判断。
 * 原因：正文是 text 列，本身能存很大，但真正要挡的是「几十 MB 的正文」——
 * 它会在入库和返回详情时各拖垮一次。这类"超大上限"用 Service 的显式常量校验更直观
 * （还能在超限时给出具体数字提示），比注解里写个 {@code max = 65535} 这种
 * 与存储实现耦合的数字要好。
 *
 * <p><b>{@code summary} 与 {@code coverImage} 都选填</b>：它们有兜底生成逻辑——
 * 摘要为空时取正文前若干字，封面为空时取正文里第一张图。
 * 所以这里不设必填，交给 Service 补。
 *
 * <p>注意：本 DTO<b>不接受 {@code status}</b>。
 * 审核状态由服务端决定（先审后发，一律先落"待审"），
 * 绝不能让前端传一个"已通过"进来——那等于把审核功能架空了。
 */
@Data
public class PostAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 帖子标题（必填，≤128）
     */
    @NotBlank(message = "帖子标题不能为空")
    @Size(max = 128, message = "帖子标题不能超过 128 个字符")
    private String title;

    /**
     * 正文（必填，Markdown；图片以内嵌形式写在其中）
     *
     * <p>图片必须先经 {@code /file/upload} 上传，拿到 {@code /uploads/...} 相对路径后再写进正文。
     * Service 会校验正文里所有图片地址必须是站内路径——不是的话直接拒绝，
     * 防止外链图成为追踪像素或将来裂图。
     */
    @NotBlank(message = "帖子正文不能为空")
    private String content;

    /**
     * 摘要（选填，≤512）：为空时由 Service 用正文前若干字兜底
     */
    @Size(max = 512, message = "帖子摘要不能超过 512 个字符")
    private String summary;

    /**
     * 封面图 URL（选填，≤512）：为空时由 Service 取正文第一张图
     */
    @Size(max = 512, message = "帖子封面地址过长")
    private String coverImage;
}

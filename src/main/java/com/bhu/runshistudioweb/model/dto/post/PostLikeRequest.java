package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 点赞请求
 *
 * 只带一个 postId：用户身份由 Sa-Token 登录态给出（Controller 取 StpUtil.getLoginIdAsLong()），
 * 请求体里绝不接收 userId——否则「替别人点赞」就成了一个功能。
 *
 * @author shaoshing
 */
@Data
public class PostLikeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 被点赞的帖子 ID
     *
     * 必须同时挂 @NotNull：@Positive 只校验非 null 的值，少了它，不传 postId 时会一路空到
     * Controller 的拆箱，变成一个 500（B0001）。
     */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 不合法")
    private Long postId;
}

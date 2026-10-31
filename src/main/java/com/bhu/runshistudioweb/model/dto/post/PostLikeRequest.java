package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 点赞请求
 *
 * @author shaoshing
 */
@Data
public class PostLikeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被点赞的帖子 ID */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 不合法")
    private Long postId;
}

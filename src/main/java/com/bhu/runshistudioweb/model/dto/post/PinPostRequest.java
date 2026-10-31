package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 置顶 / 取消置顶请求
 *
 * @author shaoshing
 */
@Data
public class PinPostRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标帖子 ID（必填） */
    @NotNull(message = "帖子 id 不能为空")
    @Positive(message = "帖子 id 必须为正整数")
    private Long id;

    /** 是否置顶（必填）：1-置顶, 0-取消置顶 */
    @NotNull(message = "置顶标记不能为空")
    private Integer pinned;
}

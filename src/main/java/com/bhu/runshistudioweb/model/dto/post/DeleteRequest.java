package com.bhu.runshistudioweb.model.dto.post;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 删除单条内容的请求
 *
 * 与 common/DeleteRequest 的区别：本类用于帖子模块，语义明确指向「删一条评论（连带删除它的回复）」。
 *
 * @author shaoshing
 */
@Data
public class DeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待删除的 ID（必填） */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;
}

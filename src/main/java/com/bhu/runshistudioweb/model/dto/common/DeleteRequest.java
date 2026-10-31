package com.bhu.runshistudioweb.model.dto.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用删除请求（按主键删除）
 *
 * @author shaoshing
 */
@Data
public class DeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待删除记录的主键 id（必填） */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;
}
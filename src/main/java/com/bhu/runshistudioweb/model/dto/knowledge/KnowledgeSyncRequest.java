package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 业务来源同步请求（把某条业务数据重新灌进知识库）
 *
 * @author shaoshing
 */
@Data
public class KnowledgeSyncRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源类型（必填）：project-项目案例 / member-成员档案 / certificate-荣誉证书 */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    /** 来源业务数据 ID（必填，必须为正数） */
    @NotNull(message = "来源 ID 不能为空")
    @Positive(message = "来源 ID 必须为正整数")
    private Long sourceId;
}

package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 项目-成员绑定 / 解绑请求体（两个接口共用）
 *
 * @author shaoshing
 */
@Data
public class ProjectMemberRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目 ID（必填，必须为正数） */
    @NotNull(message = "项目 id 不能为空")
    @Positive(message = "项目 id 必须为正整数")
    private Long projectId;

    /** 成员 ID（必填，必须为正数） */
    @NotNull(message = "成员 id 不能为空")
    @Positive(message = "成员 id 必须为正整数")
    private Long memberId;
}

package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 业务来源同步请求（把某条业务数据重新灌进知识库）
 *
 * 与手工录入的关键差别：本接口幂等且可重建。靠 (sourceType, sourceId) 定位已有文档，
 * 再比对 content_hash 决定「跳过」还是「重建」，业务数据没变时重复调用零成本（不调 Embedding、不写库）。
 *
 * manual 会被拒绝（A0401）：手工录入没有业务主键，无从做幂等，要录手工内容请走 /knowledge/doc/manual。
 * 取值合法性由 Service 用枚举校验，提示文案由 valuesText() 生成，才不会跟枚举脱节。
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

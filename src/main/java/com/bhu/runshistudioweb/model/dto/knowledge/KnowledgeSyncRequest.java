package com.bhu.runshistudioweb.model.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 业务来源同步请求（把某条业务数据重新灌进知识库）
 *
 * author: shaoshing
 *
 * <p>与手工录入的关键差别：本接口是<b>幂等且可重建</b>的。
 * 它靠 {@code (sourceType, sourceId)} 定位已有文档，
 * 再比对 {@code content_hash} 决定「跳过」还是「重建」——
 * 所以业务数据没变时重复调用是零成本的（不调 Embedding、不写库）。
 *
 * <p><b>{@code manual} 在这里会被拒绝（A0401）</b>：
 * 手工录入没有业务主键，拿什么做幂等？要录手工内容请走
 * {@code /knowledge/doc/manual}。这条校验由 Service 完成，
 * 而不是写在 DTO 的 {@code @Pattern} 里——合法取值属于「闭集」，
 * 提示文案要由 {@code KnowledgeSourceTypeEnum.valuesText()} 生成，才不会跟枚举脱节。
 */
@Data
public class KnowledgeSyncRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 来源类型（必填）：project-项目案例 / member-成员档案 / certificate-荣誉证书
     */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    /**
     * 来源业务数据 ID（必填，必须为正数）
     */
    @NotNull(message = "来源 ID 不能为空")
    @Positive(message = "来源 ID 必须为正整数")
    private Long sourceId;
}

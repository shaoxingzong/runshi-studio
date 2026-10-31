package com.bhu.runshistudioweb.model.dto.certificate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 管理端：更新证书请求（部分更新）
 *
 * @author shaoshing
 */
@Data
public class CertificateUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 id（必填） */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;

    /** 证书 / 获奖名称（选填，null 表示不修改） */
    @Size(max = 128, message = "证书名称不能超过 128 个字符")
    private String title;

    /** 级别维度（选填，null 表示不修改）：national / provincial / municipal */
    private String awardLevel;

    /** 类型维度（选填，null 表示不修改）：competition / soft_copyright / patent / paper */
    private String awardType;

    /** 获奖 / 颁发日期（选填，null 表示不修改；传了就不得晚于今天） */
    @PastOrPresent(message = "获奖日期不能晚于今天")
    private LocalDate awardDate;

    /** 证书图片 URL（选填，null 表示不修改；非空时校验长度） */
    @Size(max = 512, message = "证书图片地址过长")
    private String imageUrl;

    /** 展示置顶权重（选填，null 表示不修改） */
    private Integer sortOrder;
}

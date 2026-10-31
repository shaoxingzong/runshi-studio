package com.bhu.runshistudioweb.model.dto.certificate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 管理端：新增证书请求
 *
 * @author shaoshing
 */
@Data
public class CertificateAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 证书 / 获奖名称（必填） */
    @NotBlank(message = "证书名称不能为空")
    @Size(max = 128, message = "证书名称不能超过 128 个字符")
    private String title;

    /** 级别维度（必填）：national / provincial / municipal 取值见 com.bhu.runshistudioweb.model.enums.CertificateLevelEnum */
    @NotBlank(message = "证书级别不能为空")
    private String awardLevel;

    /** 类型维度（必填）：competition / soft_copyright / patent / paper 取值见 com.bhu.runshistudioweb.model.enums.CertificateTypeEnum */
    @NotBlank(message = "证书类型不能为空")
    private String awardType;

    /** 获奖 / 颁发日期（必填，不得晚于今天） */
    @NotNull(message = "获奖日期不能为空")
    @PastOrPresent(message = "获奖日期不能晚于今天")
    private LocalDate awardDate;

    /** 证书图片 URL（必填）一般先调 /file/upload 上传图片，再把返回的 /uploads/... 填到这里 */
    @NotBlank(message = "证书图片不能为空")
    @Size(max = 512, message = "证书图片地址过长")
    private String imageUrl;

    /** 展示置顶权重（选填，不传按 0 处理）：数值越大越靠前 */
    private Integer sortOrder;
}

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
 * 级别与类型是两个独立字段（awardLevel + awardType），不接受「国家级竞赛」这种合体字符串，
 * 因为两个维度要各自独立筛选与统计。
 *
 * awardDate 用 @PastOrPresent 在入口拦住——还没发生的获奖日期一定是填错了；
 * imageUrl 必填是因为 DDL 里该列 NOT NULL，不校验会直接抛 SQL 错误而不是给出「请上传证书图片」的提示。
 *
 * 枚举取值不用 @Pattern 写死（枚举一加取值注解就过期），合法性由 Service 用枚举校验，
 * 提示文案用 valuesText() 生成，不会跟实际取值脱节。
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

    /**
     * 级别维度（必填）：national / provincial / municipal
     * 取值见 com.bhu.runshistudioweb.model.enums.CertificateLevelEnum
     */
    @NotBlank(message = "证书级别不能为空")
    private String awardLevel;

    /**
     * 类型维度（必填）：competition / soft_copyright / patent / paper
     * 取值见 com.bhu.runshistudioweb.model.enums.CertificateTypeEnum
     */
    @NotBlank(message = "证书类型不能为空")
    private String awardType;

    /**
     * 获奖 / 颁发日期（必填，不得晚于今天）
     *
     * 用 LocalDate 接收：DDL 里该列是 date，用 LocalDateTime 会出现时间部分丢失或类型错配。
     */
    @NotNull(message = "获奖日期不能为空")
    @PastOrPresent(message = "获奖日期不能晚于今天")
    private LocalDate awardDate;

    /**
     * 证书图片 URL（必填）
     * 一般先调 /file/upload 上传图片，再把返回的 /uploads/... 填到这里
     */
    @NotBlank(message = "证书图片不能为空")
    @Size(max = 512, message = "证书图片地址过长")
    private String imageUrl;

    /** 展示置顶权重（选填，不传按 0 处理）：数值越大越靠前 */
    private Integer sortOrder;
}

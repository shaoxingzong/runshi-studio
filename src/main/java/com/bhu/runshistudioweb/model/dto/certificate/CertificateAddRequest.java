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
 * author: shaoshing
 *
 * <p>四个必填项对应本模块的四个考点：
 * <ol>
 *     <li><b>级别与类型是两个字段</b>（{@code awardLevel} + {@code awardType}）——
 *     不允许只给一个「国家级竞赛」字符串（ADR-5）；</li>
 *     <li><b>{@code awardDate} 不得晚于今天</b>：用 {@code @PastOrPresent} 在入口拦住，
 *     还没发生的获奖日期一定是填错了；</li>
 *     <li><b>{@code imageUrl} 必须非空</b>：DDL 里该列是 NOT NULL，
 *     不校验的话会直接抛 SQL 错误而不是给出「请上传证书图片」的提示；</li>
 *     <li>{@code sortOrder} 不传按 0 处理（由 Service 兜底）。</li>
 * </ol>
 *
 * <p>为什么枚举取值不用 {@code @Pattern} 写死：枚举一加取值，注解就过期了，
 * 而且错误信息还得手写一遍取值列表。枚举合法性统一交给 Service 用
 * {@link com.bhu.runshistudioweb.model.enums.CertificateLevelEnum} /
 * {@link com.bhu.runshistudioweb.model.enums.CertificateTypeEnum} 校验，
 * 提示文案用枚举的 {@code valuesText()} 生成，永远不会跟实际取值脱节。
 */
@Data
public class CertificateAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 证书 / 获奖名称（必填）
     */
    @NotBlank(message = "证书名称不能为空")
    @Size(max = 128, message = "证书名称不能超过 128 个字符")
    private String title;

    /**
     * 级别维度（必填）：national / provincial / municipal
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.CertificateLevelEnum}
     */
    @NotBlank(message = "证书级别不能为空")
    private String awardLevel;

    /**
     * 类型维度（必填）：competition / soft_copyright / patent / paper
     * 取值见 {@link com.bhu.runshistudioweb.model.enums.CertificateTypeEnum}
     */
    @NotBlank(message = "证书类型不能为空")
    private String awardType;

    /**
     * 获奖 / 颁发日期（必填，不得晚于今天）
     *
     * <p>用 {@code LocalDate} 接收：DDL 里该列是 {@code date}，
     * 用 LocalDateTime 会出现「时间部分丢失/报错」的类型错配。
     */
    @NotNull(message = "获奖日期不能为空")
    @PastOrPresent(message = "获奖日期不能晚于今天")
    private LocalDate awardDate;

    /**
     * 证书图片 URL（必填）
     * <p>一般先调 {@code /file/upload} 上传图片，再把返回的 {@code /uploads/...} 填到这里
     */
    @NotBlank(message = "证书图片不能为空")
    @Size(max = 512, message = "证书图片地址过长")
    private String imageUrl;

    /**
     * 展示置顶权重（选填，不传按 0 处理）：数值越大越靠前
     */
    private Integer sortOrder;
}

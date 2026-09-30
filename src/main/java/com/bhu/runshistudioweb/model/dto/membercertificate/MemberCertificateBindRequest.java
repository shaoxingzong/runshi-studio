package com.bhu.runshistudioweb.model.dto.membercertificate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 成员-证书绑定 / 解绑请求体（两个接口共用）
 *
 * author: shaoshing
 *
 * <p>为什么 bind 与 unbind 共用一个 DTO：两者入参完全一样（成员 ID + 证书 ID），
 * 分成两个类只会带来「改一个忘了改另一个」的风险。语义差异在 Service 方法名上，
 * 不在字段结构上。
 *
 * <p><b>为什么用 {@code Long} 接收雪花 ID</b>：Jackson 对 {@code Long} 字段同时接受
 * {@code 2104841746058252289} 与 {@code "2104841746058252289"} 两种写法
 * （字符串会自动转型），所以前端从列表接口拿到的字符串 ID 可以直接回传。
 * 而响应里由 VO 的 {@code @JsonSerialize} 统一转回字符串，前后端都不会丢精度。
 *
 * <p>{@code @Positive} 是廉价的格式兜底：能挡住 0、-1 这类明显非法的探测请求，
 * 避免它们打到数据库再返回「不存在」这种误导性提示。真正的存在性校验在 Service 里
 * （成员/证书是否存在、是否已绑定），注解表达不了那些业务规则。
 */
@Data
public class MemberCertificateBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员 ID（必填，必须为正数）
     */
    @NotNull(message = "成员 id 不能为空")
    @Positive(message = "成员 id 必须为正整数")
    private Long memberId;

    /**
     * 证书 ID（必填，必须为正数）
     */
    @NotNull(message = "证书 id 不能为空")
    @Positive(message = "证书 id 必须为正整数")
    private Long certificateId;
}

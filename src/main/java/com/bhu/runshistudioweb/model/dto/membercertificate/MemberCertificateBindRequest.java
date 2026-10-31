package com.bhu.runshistudioweb.model.dto.membercertificate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 成员-证书绑定 / 解绑请求体（两个接口共用）
 *
 * @Positive 只是格式兜底（挡住 0、-1 这类探测请求），存在性与是否已绑定由 Service 校验。
 * @author shaoshing
 */
@Data
public class MemberCertificateBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 ID（必填，必须为正数） */
    @NotNull(message = "成员 id 不能为空")
    @Positive(message = "成员 id 必须为正整数")
    private Long memberId;

    /** 证书 ID（必填，必须为正数） */
    @NotNull(message = "证书 id 不能为空")
    @Positive(message = "证书 id 必须为正整数")
    private Long certificateId;
}

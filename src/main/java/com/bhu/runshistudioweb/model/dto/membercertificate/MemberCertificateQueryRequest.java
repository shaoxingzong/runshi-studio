package com.bhu.runshistudioweb.model.dto.membercertificate;

import lombok.Data;

import java.io.Serializable;

/**
 * 成员-证书关联查询参数载体（三个查询接口共用）
 *
 * @author shaoshing
 */
@Data
public class MemberCertificateQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 ID（查「某成员的证书」时必填） */
    private Long memberId;

    /** 证书 ID（查「某证书的成员」时必填） */
    private Long certificateId;
}

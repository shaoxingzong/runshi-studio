package com.bhu.runshistudioweb.model.dto.membercertificate;

import lombok.Data;

import java.io.Serializable;

/**
 * 成员-证书关联查询参数载体（三个查询接口共用）
 *
 * 三个接口只是「从哪一端查」的差别，字段集合相同，每次只用其中一个字段
 * （按 memberId 查证书 / 按 certificateId 查成员），由 Service 自行取用并做非空校验。
 *
 * 刻意不加 @NotNull：GET 传参校验失败抛的是 BindException，全局异常处理器没接它，
 * 会落到兜底变成 B0001，而「忘了传 memberId」应该给 A0401。
 *
 * 没有分页字段：两端都是十几条量级，一次性返回即可。
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

package com.bhu.runshistudioweb.model.dto.membercertificate;

import lombok.Data;

import java.io.Serializable;

/**
 * 成员-证书关联查询参数载体（三个查询接口共用）
 *
 * author: shaoshing
 *
 * <p>共用的三个接口，每次只用其中一个字段：
 * <ul>
 *     <li>{@code /member-certificate/certificate/list?memberId=...} → 用 {@code memberId}；</li>
 *     <li>{@code /member-certificate/member/list?certificateId=...} → 用 {@code certificateId}；</li>
 *     <li>{@code /member/certificate/list?memberId=...}（C 端）→ 用 {@code memberId}。</li>
 * </ul>
 * 合到一个类里是因为三者只是「从哪一端查」的差别，字段集合相同；
 * 各接口在 Service 里自行取用需要的那个字段并做非空校验。
 *
 * <p><b>这里刻意不加 {@code @NotNull / @Valid}</b>：查询参数是 GET 传参，
 * 加校验后失败抛的是 {@code BindException}（不是 MethodArgumentNotValidException），
 * 全局异常处理器没接它 → 会落到兜底变成 50000「系统错误」，
 * 而「忘了传 memberId」明明是参数问题，应该给 40000。
 * 所以非空与合法性判断放在 Service 里用 {@code ThrowUtils.throwIf} 完成。
 *
 * <p><b>没有分页字段</b>：一名成员的证书、一张证书的署名成员都是十几条量级，
 * 一次性返回即可；真出现大列表时再按其它模块的套路加 current/pageSize。
 */
@Data
public class MemberCertificateQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员 ID（查「某成员的证书」时必填）
     */
    private Long memberId;

    /**
     * 证书 ID（查「某证书的成员」时必填）
     */
    private Long certificateId;
}

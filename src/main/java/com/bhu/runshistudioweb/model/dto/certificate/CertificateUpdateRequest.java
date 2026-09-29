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
 * author: shaoshing
 *
 * <p><b>语义是「部分更新」</b>：只有非 null 的字段会被更新，传 null 表示「这一项不动」。
 * 这依赖 MyBatis-Plus 的默认策略（null 字段不参与 UPDATE），因此前端「只改名称」时
 * 不需要先把整行查出来回传，也就不会因为漏传字段而清空数据。
 *
 * <p><b>一个容易踩的空子</b>：如果允许把 {@code imageUrl} 传空串来「清空图片」，
 * 会和「不修改」的语义混淆，而且 DDL 里该列是 NOT NULL，清空会直接违反约束。
 * 因此这里只做非空校验（传了就必须是合法值），不提供清空能力——
 * 真要换图就直接传新的 URL，真要删除就走删除接口。
 *
 * <p>安全要点：本 DTO 里的字段就是「允许修改的字段白名单」，
 * Service 侧必须显式 set 这些字段，绝不能把 DTO 整体转成实体 updateById。
 */
@Data
public class CertificateUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（必填）
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;

    /**
     * 证书 / 获奖名称（选填，null 表示不修改）
     */
    @Size(max = 128, message = "证书名称不能超过 128 个字符")
    private String title;

    /**
     * 级别维度（选填，null 表示不修改）：national / provincial / municipal
     */
    private String awardLevel;

    /**
     * 类型维度（选填，null 表示不修改）：competition / soft_copyright / patent / paper
     */
    private String awardType;

    /**
     * 获奖 / 颁发日期（选填，null 表示不修改；传了就不得晚于今天）
     */
    @PastOrPresent(message = "获奖日期不能晚于今天")
    private LocalDate awardDate;

    /**
     * 证书图片 URL（选填，null 表示不修改；非空时校验长度）
     */
    @Size(max = 512, message = "证书图片地址过长")
    private String imageUrl;

    /**
     * 展示置顶权重（选填，null 表示不修改）
     */
    private Integer sortOrder;
}

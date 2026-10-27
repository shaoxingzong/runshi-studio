package com.bhu.runshistudioweb.model.vo.certificate;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 证书视图对象（管理端列表 / 详情使用）
 *
 * 与 CertificateFrontVO 的分工：本类给管理员看，字段齐全，含运营用的 sortOrder 与审计时间；
 * 那个给游客看，只保留官网展示必须的字段。分成两个类而不是一个类加开关：
 * C 端接口是匿名的，任何多返回的字段都等于对外公开，靠「两个 VO 各写各的字段」则不可能漏。
 *
 * 类型提醒：awardDate 用 LocalDate（与实体、DDL 的 date 一致）。若写成 LocalDateTime，
 * BeanUtils.copyProperties 会因类型不匹配静默跳过，表现为管理端列表的获奖日期永远是 null，且不报任何错。
 *
 * @author shaoshing
 */
@Data
public class CertificateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 证书 ID（雪花算法 19 位，序列化为字符串避免前端精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 证书 / 获奖名称 */
    private String title;

    /**
     * 级别维度：national / provincial / municipal
     * 取值见 com.bhu.runshistudioweb.model.enums.CertificateLevelEnum
     */
    private String awardLevel;

    /**
     * 类型维度：competition / soft_copyright / patent / paper
     * 取值见 com.bhu.runshistudioweb.model.enums.CertificateTypeEnum
     */
    private String awardType;

    /**
     * 获奖 / 颁发日期（date，无时间部分）
     * 序列化格式由 JsonConfig 统一处理；这里只需要保证类型与实体一致
     */
    private LocalDate awardDate;

    /** 证书图片 URL */
    private String imageUrl;

    /** 展示置顶权重（运营配置，管理员需要看到并调整它） */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

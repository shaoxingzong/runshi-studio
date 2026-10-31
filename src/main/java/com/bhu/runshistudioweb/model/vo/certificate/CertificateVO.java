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

    /** 级别维度：national / provincial / municipal 取值见 com.bhu.runshistudioweb.model.enums.CertificateLevelEnum */
    private String awardLevel;

    /** 类型维度：competition / soft_copyright / patent / paper 取值见 com.bhu.runshistudioweb.model.enums.CertificateTypeEnum */
    private String awardType;

    /** 获奖 / 颁发日期（date，无时间部分）序列化格式由 JsonConfig 统一处理；这里只需要保证类型与实体一致 */
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

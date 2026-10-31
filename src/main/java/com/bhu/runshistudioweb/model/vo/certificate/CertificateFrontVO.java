package com.bhu.runshistudioweb.model.vo.certificate;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 证书视图对象（C 端官网展示使用，游客可获取）
 *
 * @author shaoshing
 */
@Data
public class CertificateFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 证书 ID（雪花算法 19 位，序列化为字符串避免前端精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 证书 / 获奖名称 */
    private String title;

    /** 级别维度（national / provincial / municipal）前端据此渲染「国家级/省级」标签 */
    private String awardLevel;

    /** 类型维度（competition / soft_copyright / patent / paper）前端据此把证书分组展示（竞赛类 / 软著专利类 / 论文类） */
    private String awardType;

    /** 获奖 / 颁发日期（date，无时间部分） */
    private LocalDate awardDate;

    /** 证书图片 URL（官网直接展示的图） */
    private String imageUrl;
}

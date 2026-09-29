package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 证书视图对象（C 端官网展示使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p><b>脱敏套路与 的成员 C 端 VO 一致：靠「目标 VO 没有这些字段」来实现脱敏。</b>
 * 本类刻意不包含：
 * <ul>
 *     <li>{@code sortOrder}——运营置顶权重，属于后台配置，不该由前台感知；</li>
 *     <li>{@code createdAt / updatedAt / createdBy / updatedBy / deletedAt}——审计字段，
 *     与官网展示无关。</li>
 * </ul>
 * 由于 {@code BeanUtils.copyProperties} 只会拷贝「两边都存在且类型一致」的属性，
 * 这些字段**结构上不可能**被带出去。比「拷完再手动置空」可靠：
 * 以后实体新增敏感字段时，只要不加进这个 VO，就自动被挡住。
 *
 * <p>将来官网要加展示字段时，先问一句「这个字段愿意给所有人看吗」，愿意才加到本类。
 */
@Data
public class CertificateFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 证书 ID（雪花算法 19 位，序列化为字符串避免前端精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 证书 / 获奖名称
     */
    private String title;

    /**
     * 级别维度（national / provincial / municipal）
     * <p>前端据此渲染「国家级/省级」标签
     */
    private String awardLevel;

    /**
     * 类型维度（competition / soft_copyright / patent / paper）
     * <p>前端据此把证书分组展示（竞赛类 / 软著专利类 / 论文类）
     */
    private String awardType;

    /**
     * 获奖 / 颁发日期（date，无时间部分）
     */
    private LocalDate awardDate;

    /**
     * 证书图片 URL（官网直接展示的图）
     */
    private String imageUrl;
}

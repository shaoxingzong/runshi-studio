package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 成员视图对象（管理端列表 / 详情使用）
 *
 * author: shaoshing
 *
 * <p>与 C 端 {@link MemberFrontVO} 的分工（本模块最重要的一条边界）：
 * <ul>
 *     <li>{@code MemberVO}：给**管理员**看，包含 {@code userId}、{@code sortOrder}
 *     与创建/更新时间等内部字段，便于后台判断账号绑定与置顶配置；</li>
 *     <li>{@code MemberFrontVO}：给**游客**看，只暴露官网展示需要的字段，
 *     内部字段（user_id、置顶权重、审计时间）一律不出现在结构里。</li>
 * </ul>
 * 「两个 VO 而不是一个 VO 加开关」是刻意的：字段级的 if 判断一旦某处写错，
 * 内部字段就会静默泄露到公开接口；分两个类则是「结构上不可能泄露」。
 *
 * <p>{@code id} 与 {@code userId} 显式标注 Jackson 3 的 {@code ToStringSerializer}：
 * 雪花 ID 是 19 位、超出 JS Number 安全整数范围，序列化为字符串避免前端精度丢失
 * （全局 JsonConfig 已统一处理，这里是字段级兜底）。
 */
@Data
public class MemberVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员 ID（雪花算法 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 绑定的系统用户 ID（19 位，序列化为字符串；未绑定账号时为 null）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 成员姓名
     */
    private String name;

    /**
     * 成员照片 URL
     */
    private String avatar;

    /**
     * 入学年份（如 2022）
     */
    private Integer gradeYear;

    /**
     * 专业
     */
    private String major;

    /**
     * 技术方向
     */
    private String direction;

    /**
     * 团队职务，取值见 {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}
     */
    private String teamPosition;

    /**
     * 成员状态：0-在读/在队，1-毕业/离队
     */
    private Integer memberStatus;

    /**
     * GitHub 主页
     */
    private String githubUrl;

    /**
     * 个人简介
     */
    private String summary;

    /**
     * 展示置顶权重（管理端必须能看到，否则无法核对官网排序）
     */
    private Integer sortOrder;

    /**
     * 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss）
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
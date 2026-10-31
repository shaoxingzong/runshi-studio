package com.bhu.runshistudioweb.model.vo.member;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 成员视图对象（管理端列表 / 详情使用）
 *
 * @author shaoshing
 */
@Data
public class MemberVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 ID（雪花算法 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 绑定的系统用户 ID（19 位，序列化为字符串；未绑定账号时为 null） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 成员姓名 */
    private String name;

    /** 成员照片 URL */
    private String avatar;

    /** 入学年份（如 2022） */
    private Integer gradeYear;

    /** 专业 */
    private String major;

    /** 技术方向 */
    private String direction;

    /** 团队职务，取值见 com.bhu.runshistudioweb.model.enums.TeamPositionEnum */
    private String teamPosition;

    /** 成员状态：0-在读/在队，1-毕业/离队 */
    private Integer memberStatus;

    /** GitHub 主页 */
    private String githubUrl;

    /** 个人简介 */
    private String summary;

    /** 展示置顶权重（管理端必须能看到，否则无法核对官网排序） */
    private Integer sortOrder;

    /** 创建时间（由 JsonConfig 统一序列化为 yyyy-MM-dd HH:mm:ss） */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
package com.bhu.runshistudioweb.model.dto.member;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：更新成员档案请求
 *
 * @author shaoshing
 */
@Data
public class MemberUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 id（必填） */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;

    /** 绑定的系统用户 ID（选填，null 表示不修改绑定） */
    @Positive(message = "绑定的用户 id 必须为正整数")
    private Long userId;

    /** 成员姓名（选填，null 表示不修改） */
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /** 成员照片 URL（选填，null 表示不修改） */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /** 入学年份（选填，null 表示不修改） */
    @Min(value = 1950, message = "入学年份不合法，需在 1950-2100 之间")
    @Max(value = 2100, message = "入学年份不合法，需在 1950-2100 之间")
    private Integer gradeYear;

    /** 专业（选填，null 表示不修改） */
    @Size(max = 128, message = "专业长度不能超过 128 个字符")
    private String major;

    /** 技术方向（选填，null 表示不修改） */
    @Size(max = 128, message = "技术方向长度不能超过 128 个字符")
    private String direction;

    /** 团队职务（选填，null 表示不修改），取值见 com.bhu.runshistudioweb.model.enums.TeamPositionEnum */
    private String teamPosition;

    /** 成员状态（选填，null 表示不修改）：0-在读/在队，1-毕业/离队 */
    private Integer memberStatus;

    /** GitHub 主页（选填，null 表示不修改） */
    @Size(max = 256, message = "GitHub 主页地址过长")
    private String githubUrl;

    /** 个人简介（选填，null 表示不修改） */
    @Size(max = 512, message = "个人简介长度不能超过 512 个字符")
    private String summary;

    /** 展示置顶权重（选填，null 表示不修改）：数值越大越靠前 */
    private Integer sortOrder;
}
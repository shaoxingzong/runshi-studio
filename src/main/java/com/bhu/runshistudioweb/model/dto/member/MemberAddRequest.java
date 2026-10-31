package com.bhu.runshistudioweb.model.dto.member;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端：新增成员档案请求
 *
 * @author shaoshing
 */
@Data
public class MemberAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 绑定的系统用户 ID（选填）
     *
     * 非空时由 Service 校验「账号存在且未被其他成员绑定」（唯一索引 uk_userid_deleted 兜底并发竞态）。
     */
    @Positive(message = "绑定的用户 id 必须为正整数")
    private Long userId;

    /** 成员姓名（必填） */
    @NotBlank(message = "成员姓名不能为空")
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /** 成员照片 URL（选填）一般先调 /file/upload 拿到相对路径，再填到这里 */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /** 入学年份（必填，如 2022）区间上下限与 Service 中的常量保持一致（两边写不同区间会出现两套标准） */
    @NotNull(message = "入学年份不能为空")
    @Min(value = 1950, message = "入学年份不合法，需在 1950-2100 之间")
    @Max(value = 2100, message = "入学年份不合法，需在 1950-2100 之间")
    private Integer gradeYear;

    /** 专业（选填） */
    @Size(max = 128, message = "专业长度不能超过 128 个字符")
    private String major;

    /** 技术方向（选填，如：Java后端, AI应用） */
    @Size(max = 128, message = "技术方向长度不能超过 128 个字符")
    private String direction;

    /** 团队职务（选填），取值见 com.bhu.runshistudioweb.model.enums.TeamPositionEnum 不填按成员（member）处理 */
    private String teamPosition;

    /** 成员状态（选填）：0-在读/在队，1-毕业/离队；不填按 0 处理。取值见 com.bhu.runshistudioweb.model.enums.MemberStatusEnum，合法性由 Service 用枚举校验 */
    private Integer memberStatus;

    /** GitHub 主页（选填） */
    @Size(max = 256, message = "GitHub 主页地址过长")
    private String githubUrl;

    /** 个人简介（选填） */
    @Size(max = 512, message = "个人简介长度不能超过 512 个字符")
    private String summary;

    /** 展示置顶权重（选填）：数值越大越靠前；不填按 0 处理 */
    private Integer sortOrder;
}
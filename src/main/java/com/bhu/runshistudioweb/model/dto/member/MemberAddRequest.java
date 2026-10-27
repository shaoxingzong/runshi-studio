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
 * 本类接口必须挂在 @SaCheckRole("admin") 之下（见 StudioMemberController），
 * 成员档案是官网展示的权威数据源，只有管理员能写。
 *
 * gradeYear 用 @NotNull + @Min/@Max 而不是 @Pattern：它是 smallint（Java 侧 Integer），
 * 前端把「2026级」这种字符串塞进来会在反序列化阶段失败，由全局异常处理器统一转成 A0401；
 * 区间 1950~2100 挡住 1800、3200 这类明显不可能的输入。
 *
 * teamPosition / memberStatus 不做注解白名单，合法性由 Service 用枚举校验——写死在注解里枚举一改就过期。
 *
 * @author shaoshing
 */
@Data
public class MemberAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 绑定的系统用户 ID（选填）
     *
     * 不填的后果：成员功能的准入判据是「档案的 user_id 命中当前登录账号」，user_id 为 NULL 的档案
     * 永远不会生效——本人登录后看不到任何成员功能，而管理员在列表里却看到「这个人是成员」，
     * 且不报错、不提示。允许为空是因为历史导入、先建档后开号的场景需要。
     *
     * 非空时由 Service 校验「账号存在且未被其他成员绑定」（唯一索引 uk_userid_deleted 兜底并发竞态）。
     */
    @Positive(message = "绑定的用户 id 必须为正整数")
    private Long userId;

    /** 成员姓名（必填） */
    @NotBlank(message = "成员姓名不能为空")
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /**
     * 成员照片 URL（选填）
     * 一般先调 /file/upload 拿到相对路径，再填到这里
     */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /**
     * 入学年份（必填，如 2022）
     * 区间上下限与 Service 中的常量保持一致（两边写不同区间会出现两套标准）
     */
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

    /**
     * 团队职务（选填），取值见 com.bhu.runshistudioweb.model.enums.TeamPositionEnum
     * 不填按成员（member）处理
     */
    private String teamPosition;

    /**
     * 成员状态（选填）：0-在读/在队，1-毕业/离队；不填按 0 处理
     * 取值见 com.bhu.runshistudioweb.model.enums.MemberStatusEnum，合法性由 Service 用枚举校验
     */
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
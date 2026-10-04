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
 * author: shaoshing
 *
 * <p>本类的接口必须挂在 {@code @SaCheckRole("admin")} 之下（见 StudioMemberController），
 * 成员档案是官网展示的权威数据源，只有管理员能写。
 *
 * <p>三个重点字段的约束设计：
 * <ul>
 *     <li>{@code gradeYear} 用 {@code @NotNull + @Min/@Max} 而不是 {@code @Pattern}：
 *     它是 smallint（Java 侧 Integer），如果前端把「2026级」这种字符串塞进来，
 *     Jackson 反序列化阶段就会失败，由全局异常处理器统一转成 A0401，
 *     不会出现「字符串混进数字列」的脏数据；区间 1950~2100 是业务上合理的范围，
 *     挡住 1800、3200 这类明显不可能的输入；</li>
 *     <li>{@code teamPosition / memberStatus} 不做注解白名单，只声明「选填」，
 *     合法性由 Service 用枚举校验——把取值写死在注解里的话，枚举一改注解就过期了；</li>
 *     <li>{@code userId} 绑定账号是可选动作，非空时由 Service 校验
 *     「账号存在且未被其他成员绑定」（唯一索引 uk_userid_deleted 兜底并发竞态）。</li>
 * </ul>
 */
@Data
public class MemberAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 绑定的系统用户 ID（选填）
     * <p>不填表示该成员尚未开通登录账号；填了就必须是真实存在且未被占用的账号
     */
    @Positive(message = "绑定的用户 id 必须为正整数")
    private Long userId;

    /**
     * 成员姓名（必填）
     */
    @NotBlank(message = "成员姓名不能为空")
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /**
     * 成员照片 URL（选填）
     * <p>一般先调 {@code /file/upload} 拿到相对路径，再填到这里
     */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /**
     * 入学年份（必填，如 2022）
     * <p>区间上下限与 Service 中的常量保持一致（两边写不同区间会出现两套标准）
     */
    @NotNull(message = "入学年份不能为空")
    @Min(value = 1950, message = "入学年份不合法，需在 1950-2100 之间")
    @Max(value = 2100, message = "入学年份不合法，需在 1950-2100 之间")
    private Integer gradeYear;

    /**
     * 专业（选填）
     */
    @Size(max = 128, message = "专业长度不能超过 128 个字符")
    private String major;

    /**
     * 技术方向（选填，如：Java后端, AI应用）
     */
    @Size(max = 128, message = "技术方向长度不能超过 128 个字符")
    private String direction;

    /**
     * 团队职务（选填），取值见 {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}
     * <p>不填按成员（member）处理
     */
    private String teamPosition;

    /**
     * 成员状态（选填）：0-在读/在队，1-毕业/离队；不填按 0 处理
     * <p>取值见 {@link com.bhu.runshistudioweb.model.enums.MemberStatusEnum}
     */
    private Integer memberStatus;

    /**
     * GitHub 主页（选填）
     */
    @Size(max = 256, message = "GitHub 主页地址过长")
    private String githubUrl;

    /**
     * 个人简介（选填）
     */
    @Size(max = 512, message = "个人简介长度不能超过 512 个字符")
    private String summary;

    /**
     * 展示置顶权重（选填）：数值越大越靠前；不填按 0 处理
     */
    private Integer sortOrder;
}
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
 * 本类的接口必须挂在 @SaCheckRole("admin") 之下（见 StudioMemberController），
 * 成员档案是官网展示的权威数据源，只有管理员能写。
 *
 * 三个重点字段的约束设计：
 * - gradeYear 用 @NotNull + @Min/@Max 而不是 @Pattern：
 * 它是 smallint（Java 侧 Integer），如果前端把「2026级」这种字符串塞进来，
 * Jackson 反序列化阶段就会失败，由全局异常处理器统一转成 A0401，
 * 不会出现「字符串混进数字列」的脏数据；区间 1950~2100 是业务上合理的范围，
 * 挡住 1800、3200 这类明显不可能的输入；
 * - teamPosition / memberStatus 不做注解白名单，只声明「选填」，
 * 合法性由 Service 用枚举校验——把取值写死在注解里的话，枚举一改注解就过期了；
 * - userId 绑定账号仍是<b>选填</b>（见下方字段注释，这是刻意保留的）。
 *
 * <p><b>为什么 userId 是选填而不是必填</b>
 * 这里最容易踩的坑，是把它当成「两套身份判据」中的一套：成员功能的准入判据是
 * <b>查 {@code studio_member} 档案（认表）</b>，而后台入口 / 角色标签查的是
 * {@code sys_user.user_role}（认角色）。两者不同步时，就会出现「后台创建的成员
 * 有时候不显示功能、有时候显示了也报错」这类现象。
 *
 * <p>一个看似直接的修法是把 userId 改成必填，但那会破坏既有设计——
 * {@code db/user.sql} 明确写着「<b>也可以有档案没账号（历史导入）</b>」，
 * 强制必填属于过度修正。
 *
 * <p>因此这里<b>保留选填，但把「不绑定的后果」讲清楚</b>：
 * <ul>
 *     <li>新增时不绑账号仍然允许（历史导入 / 先建档后开号都靠它）；</li>
 *     <li>但管理端在授予 member / admin 角色时，会校验「该账号必须有档案」，
 *     否则拒绝并提示先去建档——这一步堵住了「有角色没档案」的坏状态；</li>
 *     <li>前端在新增表单里把「不绑定将不会生效」写成醒目提示。</li>
 * </ul>
 * 这样既不改既有契约，又让「档案是否真正生效」在流程上可被感知。
 *
 * @author shaoshing
 */
@Data
public class MemberAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 绑定的系统用户 ID（<b>选填</b>）
     *
     * <p><b>不填的后果（管理端必须知道）</b>：成员功能的准入判据是
     * 「档案的 {@code user_id} 命中当前登录账号」。{@code user_id} 为 NULL 的档案
     * <b>永远不会生效</b>——本人登录后看不到任何成员功能，而管理员在列表里
     * 却看到「这个人是成员」，且不报错、不提示。
     *
     * <p>因此它虽然允许为空（历史导入、先建档后开号的场景需要），
     * 但<b>面向普通使用时应尽量绑定</b>；前端表单已把这条写成醒目提示。
     *
     * <p>非空时由 Service 校验「账号存在且未被其他成员绑定」
     * （唯一索引 {@code uk_userid_deleted} 兜底并发竞态）。
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
     * 取值见 com.bhu.runshistudioweb.model.enums.MemberStatusEnum
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
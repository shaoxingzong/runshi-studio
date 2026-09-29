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
 * author: shaoshing
 *
 * <p><b>语义是「部分更新」</b>：只有非 null 的字段才会被更新，传 null 表示「这一项不动」。
 * 这依赖 MyBatis-Plus 的默认策略（null 字段不参与 UPDATE），因此：
 * <ul>
 *     <li>前端做「只改头像」的表单时，不必先查整行再回传，避免自己漏字段导致误清空；</li>
 *     <li>代价是**无法通过本接口把某个字段清空**（例如解除账号绑定、清掉简介），
 *     真有这种需求要单独设计语义明确的接口，而不是用传空串来模糊表达；</li>
 *     <li>正因为「传 null = 不修改」，{@code userId} 一旦不动就保持原绑定，
 *     但要改绑时必须校验新账号「存在且未被其他成员占用」（Service 里排除自己后校验）。</li>
 * </ul>
 *
 * <p>{@code gradeYear} 与新增请求同一套区间（1950~2100）；
 * {@code teamPosition / memberStatus} 的合法性由 Service 用枚举校验。
 */
@Data
public class MemberUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（必填）
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正整数")
    private Long id;

    /**
     * 绑定的系统用户 ID（选填，null 表示不修改绑定）
     */
    @Positive(message = "绑定的用户 id 必须为正整数")
    private Long userId;

    /**
     * 成员姓名（选填，null 表示不修改）
     */
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /**
     * 成员照片 URL（选填，null 表示不修改）
     */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /**
     * 入学年份（选填，null 表示不修改）
     */
    @Min(value = 1950, message = "入学年份不合法，需在 1950-2100 之间")
    @Max(value = 2100, message = "入学年份不合法，需在 1950-2100 之间")
    private Integer gradeYear;

    /**
     * 专业（选填，null 表示不修改）
     */
    @Size(max = 128, message = "专业长度不能超过 128 个字符")
    private String major;

    /**
     * 技术方向（选填，null 表示不修改）
     */
    @Size(max = 128, message = "技术方向长度不能超过 128 个字符")
    private String direction;

    /**
     * 团队职务（选填，null 表示不修改），取值见
     * {@link com.bhu.runshistudioweb.model.enums.TeamPositionEnum}
     */
    private String teamPosition;

    /**
     * 成员状态（选填，null 表示不修改）：0-在读/在队，1-毕业/离队
     */
    private Integer memberStatus;

    /**
     * GitHub 主页（选填，null 表示不修改）
     */
    @Size(max = 256, message = "GitHub 主页地址过长")
    private String githubUrl;

    /**
     * 个人简介（选填，null 表示不修改）
     */
    @Size(max = 512, message = "个人简介长度不能超过 512 个字符")
    private String summary;

    /**
     * 展示置顶权重（选填，null 表示不修改）：数值越大越靠前
     */
    private Integer sortOrder;
}
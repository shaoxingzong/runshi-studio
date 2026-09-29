package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 成员视图对象（C 端官网展示使用，游客可获取）
 *
 * author: shaoshing
 *
 * <p><b>本类存在的唯一理由：公开接口的字段白名单</b>。
 * C 端成员列表是**匿名可访问**的接口，任何多返回的字段都等于公开数据，
 * 因此这里刻意只有「官网展示需要的信息」，内部字段一个都不带：
 * <ul>
 *     <li>{@code userId}（账号绑定关系）——属于内部管理信息，
 *     公开它等于告诉外界「这个成员对应哪个登录账号」，属于不必要的信息暴露；</li>
 *     <li>{@code sortOrder}（置顶权重）——属于运营配置，不该由前台感知；</li>
 *     <li>{@code createdAt / updatedAt / createdBy / updatedBy / deletedAt}——审计字段，
 *     与官网展示无关。</li>
 * </ul>
 * 将来官网要加展示字段时，**先问「这个字段愿意给所有人看吗」**，愿意才加到这里。
 *
 * <p>{@code id} 仍需序列化为字符串（雪花 ID 19 位超 JS 安全整数范围），
 * 前端用它与 {@code /member/...} 详情/证书接口对接。
 */
@Data
public class MemberFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员 ID（雪花算法 19 位，序列化为字符串避免前端精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 成员姓名
     */
    private String name;

    /**
     * 成员照片 URL
     */
    private String avatar;

    /**
     * 入学年份（如 2022）：官网按届别分组展示时使用
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
     * 团队职务（member / leader / tech_lead），前端据此渲染「队长/组长」标签
     */
    private String teamPosition;

    /**
     * 成员状态：0-在读/在队，1-毕业/离队
     * <p>官网友情链接与「已毕业成员」板块需要区分展示，故保留；
     * 它不含任何内部信息，暴露给游客没有风险
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
}
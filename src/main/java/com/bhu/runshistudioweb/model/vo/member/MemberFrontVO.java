package com.bhu.runshistudioweb.model.vo.member;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 成员视图对象（C 端官网展示使用，游客可获取）
 *
 * @author shaoshing
 */
@Data
public class MemberFrontVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员 ID（雪花算法 19 位，序列化为字符串避免前端精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 成员姓名 */
    private String name;

    /** 成员照片 URL */
    private String avatar;

    /** 入学年份（如 2022）：官网按届别分组展示时使用 */
    private Integer gradeYear;

    /** 专业 */
    private String major;

    /** 技术方向 */
    private String direction;

    /** 团队职务（member / leader / tech_lead），前端据此渲染「队长/组长」标签 */
    private String teamPosition;

    /** 成员状态：0-在读/在队，1-毕业/离队。官网友情链接与「已毕业成员」板块需要区分展示，故保留；它不含任何内部信息，暴露给游客没有风险 */
    private Integer memberStatus;

    /** GitHub 主页 */
    private String githubUrl;

    /** 个人简介 */
    private String summary;
}
package com.bhu.runshistudioweb.model.dto.member;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 成员自助编辑本人档案请求
 *
 * <p>刻意只包含「展示字段」：id / userId / gradeYear / teamPosition / memberStatus /
 * sortOrder 不在 DTO 中——从结构上杜绝成员改绑账号、自封队长或改「已毕业」状态。
 * 所有字段可为 null（表示不修改）。
 *
 * @author shaoshing
 */
@Data
public class MemberSelfUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员姓名（选填，null 表示不修改；传值则 trim 后不能为空） */
    @Size(max = 64, message = "成员姓名长度不能超过 64 个字符")
    private String name;

    /** 成员照片 URL（选填，null 表示不修改） */
    @Size(max = 512, message = "成员照片地址过长")
    private String avatar;

    /** 专业（选填，null 表示不修改） */
    @Size(max = 128, message = "专业长度不能超过 128 个字符")
    private String major;

    /** 技术方向（选填，null 表示不修改） */
    @Size(max = 128, message = "技术方向长度不能超过 128 个字符")
    private String direction;

    /** GitHub 主页（选填，null 表示不修改） */
    @Size(max = 256, message = "GitHub 主页地址过长")
    private String githubUrl;

    /** 个人简介（选填，null 表示不修改） */
    @Size(max = 512, message = "个人简介长度不能超过 512 个字符")
    private String summary;
}

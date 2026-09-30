package com.bhu.runshistudioweb.model.dto.project;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 项目-成员绑定 / 解绑请求体（两个接口共用）
 *
 * author: shaoshing
 *
 * <p>与 {@code MemberCertificateBindRequest} 同一套路：bind 与 unbind 入参完全相同，
 * 共用一个类避免「改一个忘了改另一个」；语义差异（新增 vs 删除、冲突时报错 vs 未绑定报错）
 * 由 Service 方法区分，不体现在字段结构上。
 *
 * <p><b>注意与「队长同步」的区别</b>：本 DTO 走的是
 * {@code MemberProjectService#bindMember}——重复绑定会返回 40000，
 * 因为这是管理员的<b>显式动作</b>，冲突必须被看见。
 * 而新增/修改项目时同步队长用的是幂等的 {@code ensureMemberInProject}
 * （已存在就直接返回），两者不能混用——换队长若走 bind，
 * 管理员每次重复保存都会收到 40000。
 *
 * <p>用 {@code Long} 接收雪花 ID：Jackson 对 Long 字段同时接受数字与数字字符串，
 * 前端从列表接口拿到的字符串 ID 可以直接回传。
 */
@Data
public class ProjectMemberRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（必填，必须为正数）
     */
    @NotNull(message = "项目 id 不能为空")
    @Positive(message = "项目 id 必须为正整数")
    private Long projectId;

    /**
     * 成员 ID（必填，必须为正数）
     */
    @NotNull(message = "成员 id 不能为空")
    @Positive(message = "成员 id 必须为正整数")
    private Long memberId;
}

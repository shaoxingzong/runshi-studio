package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.project.ProjectMemberRequest;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;
import com.bhu.runshistudioweb.service.MemberProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员-项目关联接口（<b>全部为管理端</b>）
 *
 * author: shaoshing
 *
 * <p>这里维护「谁参与了这个项目」。注意它<b>不是</b>队长的权威源——
 * 队长由 {@code studio_project.leader_id} 决定（见 ProjectController），
 * 新增/修改项目时由 Service 在同一事务内把队长同步到本表，
 * 保证「队长必然出现在参与成员列表中」。
 *
 * <p>因此本类有两个必须说明的接口行为：
 * <ul>
 *     <li>{@code /bind} 是<b>管理员显式添加</b>：重复绑定返回 A0401，冲突必须被看见；</li>
 *     <li>{@code /unbind} 会拦住「移除当前队长」（A0401），
 *     否则会打破上面那个不变量；要换队长请先走 {@code /project/update}。</li>
 * </ul>
 *
 * <p>本模块没有 C 端接口：官网的「项目参与成员」暂不在本期展示范围内，
 * 将来若需要，按成员/证书模块的套路加一个精确白名单路径即可。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/member-project/bind}。
 */
@Tag(name = "成员项目关联", description = "管理员维护项目与参与成员的关联关系")
@RestController
@RequestMapping("/member-project")
public class MemberProjectController {

    @Resource
    private MemberProjectService memberProjectService;

    /**
     * 绑定成员到项目（管理员显式添加）
     *
     * @param projectMemberRequest 绑定请求（projectId 与 memberId 均必填）
     * @return true 表示绑定成功；重复绑定返回 A0401
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员到项目", description = "项目与成员都必须存在；重复绑定返回 A0401")
    public BaseResponse<Boolean> bindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(memberProjectService.bindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 解绑成员（物理删除关联行）
     *
     * <p><b>当前队长不可解绑</b>（返回 A0401「队长不能从参与成员中移除，请先更换队长」）：
     * 这是保护「队长必然在参与成员列表」不变量的守卫。
     *
     * @param projectMemberRequest 解绑请求（与绑定共用同一个请求体结构）
     * @return true 表示解绑成功
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑项目成员", description = "未参与返回 A0402；当前队长不可解绑（A0401）")
    public BaseResponse<Boolean> unbindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(memberProjectService.unbindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 查参与某项目的全部成员
     *
     * @param projectId 项目 ID
     * @return 成员列表（管理端视图），按 sort_order → id 倒序；项目不存在返回 A0402
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查项目的参与成员", description = "项目不存在返回 A0402；队长也在列表中")
    public BaseResponse<List<MemberVO>> listMembersByProject(@RequestParam("projectId") Long projectId) {
        // 用包装类型接参 + 显式判空：基本类型遇到「没传参数」会抛
        // MissingServletRequestParameterException，全局处理器没接它 → 变成 B0001 系统错误；
        // 这里才能给出「项目 id 不能为空」这种准确的 A0401
        ThrowUtils.throwIf(projectId == null, ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        return ResultUtils.success(memberProjectService.listMembersByProject(projectId));
    }

    /**
     * 查某成员参与的全部项目（反向查询）
     *
     * <p>入参名用 {@code memberId} 而不是复用 {@code projectId}：
     * 两个接口的参数含义相反，名字写清楚才不会被前端调错。
     *
     * @param memberId 成员 ID
     * @return 项目列表（管理端视图），按 sort_order → created_at → id 倒序；成员不存在返回 A0402
     */
    @GetMapping("/project/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员参与的项目", description = "成员不存在返回 A0402")
    public BaseResponse<List<ProjectVO>> listProjectsByMember(@RequestParam("memberId") Long memberId) {
        ThrowUtils.throwIf(memberId == null, ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        return ResultUtils.success(memberProjectService.listProjectsByMember(memberId));
    }
}

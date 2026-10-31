package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.project.ProjectMemberRequest;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;
import com.bhu.runshistudioweb.service.MemberProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员-项目关联接口（全部为管理端）
 *
 * @author shaoshing
 */
@Tag(name = "成员项目关联", description = "管理员维护项目与参与成员的关联关系")
@RestController
@RequestMapping("/member-project")
@RequiredArgsConstructor
public class MemberProjectController {

    private final MemberProjectService memberProjectService;

    /**
     * 绑定成员到项目（管理员显式添加）
     *
     * @param projectMemberRequest projectId 与 memberId 均必填且为正整数
     * @return true 表示绑定成功
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员到项目", description = "项目与成员都必须存在；重复绑定返回 A0401")
    public BaseResponse<Boolean> bindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 显式动作必须报冲突：与内部同步用的「保证在里面」是两种语义，不能混用
        return ResultUtils.success(memberProjectService.bindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 解绑成员（物理删除关联行）
     *
     * @param projectMemberRequest projectId 与 memberId 均必填且为正整数
     * @return true 表示解绑成功
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑项目成员", description = "未参与返回 A0402；当前队长不可解绑（A0401）")
    public BaseResponse<Boolean> unbindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 队长守卫在 Service 里读 studio_project.leader_id，Controller 不重复判
        return ResultUtils.success(memberProjectService.unbindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 查参与某项目的全部成员
     *
     * @param projectId 项目 ID，必填且必须大于 0
     * @return 成员列表（MemberVO，含队长）；项目没有成员时返回空列表
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查项目的参与成员", description = "项目不存在返回 A0402；队长也在列表中")
    public BaseResponse<List<MemberVO>> listMembersByProject(@RequestParam("projectId") Long projectId) {
        ThrowUtils.throwIf(projectId == null, ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        // 先校验项目存在再查成员：顺序反了会把「项目不存在」伪装成「还没有成员」
        return ResultUtils.success(memberProjectService.listMembersByProject(projectId));
    }

    /**
     * 查某成员参与的全部项目（反向查询）
     *
     * @param memberId 成员 ID，必填且必须大于 0
     * @return 项目列表（ProjectVO，含正文与队长）；没有参与项目时返回空列表
     */
    @GetMapping("/project/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员参与的项目", description = "成员不存在返回 A0402")
    public BaseResponse<List<ProjectVO>> listProjectsByMember(@RequestParam("memberId") Long memberId) {
        ThrowUtils.throwIf(memberId == null, ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        // 先校验成员存在再查项目：否则「成员不存在」会被伪装成「这人没有项目」
        return ResultUtils.success(memberProjectService.listProjectsByMember(memberId));
    }
}

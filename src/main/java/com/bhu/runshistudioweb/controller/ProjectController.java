package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectAddRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectQueryRequest;
import com.bhu.runshistudioweb.model.dto.project.ProjectUpdateRequest;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.project.ProjectVO;
import com.bhu.runshistudioweb.service.StudioProjectService;
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

/**
 * 项目案例接口：C 端（官网展示）+ 管理端（管理员增删改查）
 *
 * @author shaoshing
 */
@Tag(name = "项目案例模块", description = "官网项目展示（匿名可访问）与管理员项目管理")
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectController {

    private final StudioProjectService studioProjectService;

    // C 端：官网展示，匿名可访问

    /**
     * 官网项目列表（游客可访问，真分页）
     *
     * @param projectQueryRequest title 模糊、status 精确 + 分页；允许整体为 null
     * @return 分页结果（ProjectFrontVO，无正文）；没有项目时 records 是空列表
     */
    @GetMapping("/list")
    @Operation(summary = "官网项目列表", description = "匿名可访问；支持标题模糊与状态筛选 + 真分页，固定按置顶权重倒序")
    public BaseResponse<Page<ProjectFrontVO>> listFrontProjects(@Valid ProjectQueryRequest projectQueryRequest) {
        return ResultUtils.success(studioProjectService.listFrontProjects(projectQueryRequest));
    }

    /**
     * 官网项目详情（游客可访问）
     *
     * @param id 项目 ID，必须大于 0
     * @return 详情（列表字段 + Markdown 正文 + 参与成员）；不存在抛 A0402
     */
    @GetMapping("/detail")
    @Operation(summary = "官网项目详情", description = "匿名可访问；返回含 Markdown 正文的详情，不存在返回 A0402")
    public BaseResponse<ProjectFrontDetailVO> getFrontProjectById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // 成员列表在 Service 里装配，用的是脱敏 VO，不含账号 ID 等内部字段
        return ResultUtils.success(studioProjectService.getFrontProjectById(id));
    }

    // 管理端：全部要求 admin 角色

    /**
     * 管理员新增项目
     *
     * @param projectAddRequest title、description、leaderId 必填，其余选填
     * @return 新项目 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增项目", description = "队长必须存在；同事务同步队长到参与成员列表")
    public BaseResponse<Long> addProject(@RequestBody @Valid ProjectAddRequest projectAddRequest) {
        // @Valid 只保证字段级约束被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 主表写与队长同步在同一个事务里，要么都成功要么都回滚
        return ResultUtils.success(studioProjectService.addProject(projectAddRequest));
    }

    /**
     * 管理员更新项目（部分更新：字段为 null 表示不修改）
     *
     * @param projectUpdateRequest id 必填，其余为 null 表示保持原值；传 leaderId 即换队长
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新项目", description = "null 表示不修改；换队长时同事务同步新队长，旧队长保留")
    public BaseResponse<Boolean> updateProject(@RequestBody @Valid ProjectUpdateRequest projectUpdateRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 「旧队长保留」是刻意的：卸任不等于退出项目，是否移除由管理员显式决定
        return ResultUtils.success(studioProjectService.updateProject(projectUpdateRequest));
    }

    /**
     * 管理员删除项目（逻辑删除）
     *
     * @param deleteRequest id 必填且为正整数
     * @return true 只代表 UPDATE 命中了行，数据并没有从库里消失
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除项目", description = "逻辑删除；同事务清理成员-项目关联，数据可追溯")
    public BaseResponse<Boolean> deleteProject(@RequestBody @Valid DeleteRequest deleteRequest) {
        // 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 清关联与删主表在同一事务里，顺序反了会留下悬空关联
        return ResultUtils.success(studioProjectService.deleteProject(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询项目详情
     *
     * @param id 项目 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return ProjectVO（含正文、队长、置顶权重与首页排名）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询项目详情")
    public BaseResponse<ProjectVO> getProjectById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // 管理端 VO 含正文与内部字段，因此不能把它复用到任何匿名接口上
        return ResultUtils.success(studioProjectService.getProjectById(id));
    }

    /**
     * 管理员分页查询项目
     *
     * @param projectQueryRequest title 模糊，status、leaderId 精确 + 分页与排序；允许整体为 null
     * @return 分页结果（ProjectVO）；无数据时 records 是空列表
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询项目", description = "支持标题模糊、状态与队长精确筛选，每页最多 50 条")
    public BaseResponse<Page<ProjectVO>> listProjectByPage(@Valid ProjectQueryRequest projectQueryRequest) {
        return ResultUtils.success(studioProjectService.listProjectByPage(projectQueryRequest));
    }
}

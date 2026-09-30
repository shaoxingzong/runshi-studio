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
import com.bhu.runshistudioweb.model.vo.ProjectFrontDetailVO;
import com.bhu.runshistudioweb.model.vo.ProjectFrontVO;
import com.bhu.runshistudioweb.model.vo.ProjectVO;
import com.bhu.runshistudioweb.service.StudioProjectService;
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

/**
 * 项目案例接口：C 端（官网展示）+ 管理端（管理员增删改查）
 *
 * author: shaoshing
 *
 * <p>Controller 只做三件事——接参、调 Service、包成 {@link BaseResponse}；
 * 校验与业务规则全在 Service 层。
 *
 * <p><b>两组接口的界线（本类最重要的约定）</b>：
 * <table border="1">
 *     <caption>接口分组</caption>
 *     <tr><th>分组</th><th>路径</th><th>鉴权</th></tr>
 *     <tr><td>C 端</td><td>{@code /project/list}、{@code /project/detail}</td>
 *     <td><b>匿名可访问</b>——已加入 Sa-Token 白名单的<b>精确路径</b>
 *     （见 SaTokenMvcConfig），官网游客必须能看到项目案例页</td></tr>
 *     <tr><td>管理端</td><td>{@code /project/add}、{@code /project/update}、
 *     {@code /project/delete}、{@code /project/get}、{@code /project/list/page}</td>
 *     <td>全部要求 {@code @SaCheckRole("admin")}</td></tr>
 * </table>
 *
 * <p><b>两个容易看混的路径</b>：{@code /project/list}（C 端，匿名，只返回脱敏列表）
 * 与 {@code /project/list/page}（管理端，要求 admin，返回全字段）。
 * 白名单里只登记前者，且必须写精确路径——写成 {@code /project/**}
 * 会把管理端接口一起放行。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/project/list}。
 */
@Tag(name = "项目案例模块", description = "官网项目展示（匿名可访问）与管理员项目管理")
@RestController
@RequestMapping("/project")
public class ProjectController {

    @Resource
    private StudioProjectService studioProjectService;

    // ==================== C 端：官网展示，匿名可访问 ====================

    /**
     * 官网项目列表（游客可访问，真分页）
     *
     * <p>筛选：{@code title} 模糊、{@code status} 精确；
     * 忽略请求里的 sortField / sortOrder（固定 置顶权重正序…见下），
     * pageSize 上限 50。
     *
     * <p>返回的 {@link ProjectFrontVO} <b>不含 content</b>：
     * 它是 Markdown 正文大字段，列表带上会让每行都回传整篇正文，
     * 只有详情接口才取。
     *
     * @param projectQueryRequest 查询条件（title / status / current / pageSize），允许为空
     * @return 分页结果，记录为脱敏的 {@link ProjectFrontVO}
     */
    @GetMapping("/list")
    @Operation(summary = "官网项目列表", description = "匿名可访问；支持标题模糊与状态筛选 + 真分页，固定按置顶权重倒序")
    public BaseResponse<Page<ProjectFrontVO>> listFrontProjects(ProjectQueryRequest projectQueryRequest) {
        return ResultUtils.success(studioProjectService.listFrontProjects(projectQueryRequest));
    }

    /**
     * 官网项目详情（游客可访问）
     *
     * <p>与列表的唯一区别是<b>返回 content 正文</b>——所以详情单独一个 VO，
     * 而不是让列表带一个「要不要正文」的开关。
     *
     * @param id 项目 ID
     * @return 脱敏详情（列表字段 + content）；不存在返回 40400
     */
    @GetMapping("/detail")
    @Operation(summary = "官网项目详情", description = "匿名可访问；返回含 Markdown 正文的详情，不存在返回 40400")
    public BaseResponse<ProjectFrontDetailVO> getFrontProjectById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        return ResultUtils.success(studioProjectService.getFrontProjectById(id));
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 管理员新增项目
     *
     * <p>新增时会<b>在同一事务内把队长写入成员-项目关联表</b>（DESIGN 2.3），
     * 保证「队长必然出现在参与成员列表中」。
     *
     * @param projectAddRequest 新增请求（title / description / leaderId 必填）
     * @return 新项目 ID
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增项目", description = "队长必须存在；同事务同步队长到参与成员列表")
    public BaseResponse<Long> addProject(@RequestBody @Valid ProjectAddRequest projectAddRequest) {
        // @Valid 只保证字段级约束被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioProjectService.addProject(projectAddRequest));
    }

    /**
     * 管理员更新项目（部分更新：字段为 null 表示不修改）
     *
     * @param projectUpdateRequest 更新请求（id 必填；传 leaderId 即换队长）
     * @return true 表示更新成功
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新项目", description = "null 表示不修改；换队长时同事务同步新队长，旧队长保留")
    public BaseResponse<Boolean> updateProject(@RequestBody @Valid ProjectUpdateRequest projectUpdateRequest) {
        ThrowUtils.throwIf(projectUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioProjectService.updateProject(projectUpdateRequest));
    }

    /**
     * 管理员删除项目（逻辑删除）
     *
     * @param deleteRequest 删除请求（id 必填）
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除项目", description = "逻辑删除；同事务清理成员-项目关联，数据可追溯")
    public BaseResponse<Boolean> deleteProject(@RequestBody @Valid DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioProjectService.deleteProject(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询项目详情
     *
     * @param id 项目 ID
     * @return 含 content / leaderId / sortOrder / 审计字段的项目信息
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询项目详情")
    public BaseResponse<ProjectVO> getProjectById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        return ResultUtils.success(studioProjectService.getProjectById(id));
    }

    /**
     * 管理员分页查询项目
     *
     * <p>筛选：{@code title} 模糊、{@code status} 精确、{@code leaderId} 精确；
     * 排序字段走白名单，默认 {@code sort_order DESC → created_at DESC → id DESC}。
     *
     * @param projectQueryRequest 查询条件，允许为空
     * @return 分页结果，记录为含内部字段的 {@link ProjectVO}
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询项目", description = "支持标题模糊、状态与队长精确筛选，每页最多 50 条")
    public BaseResponse<Page<ProjectVO>> listProjectByPage(ProjectQueryRequest projectQueryRequest) {
        return ResultUtils.success(studioProjectService.listProjectByPage(projectQueryRequest));
    }
}

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
 * 两组界线：/project/list、/project/detail 匿名可访问（白名单精确登记）；/add、/update、
 * /delete、/get、/list/page 要求 admin。两者只差一个后缀，登记时不能省略。
 *
 * VO 分三档：ProjectFrontVO、ProjectFrontDetailVO（含正文与成员）、ProjectVO。
 *
 * @author shaoshing
 */
@Tag(name = "项目案例模块", description = "官网项目展示（匿名可访问）与管理员项目管理")
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectController {

    private final StudioProjectService studioProjectService;

    // ==================== C 端：官网展示，匿名可访问 ====================

    /**
     * 官网项目列表（游客可访问，真分页）
     *
     * 排序固定为「置顶权重 → 创建时间 → id 倒序」，请求里的 sortField / sortOrder 一律忽略：
     * 允许匿名请求指定排序，等于允许任何人把某个项目顶到官网第一位。
     *
     * @param projectQueryRequest title 模糊、status 精确 + 分页；允许整体为 null
     * @return 分页结果（ProjectFrontVO，无正文）；没有项目时 records 是空列表
     */
    @GetMapping("/list")
    @Operation(summary = "官网项目列表", description = "匿名可访问；支持标题模糊与状态筛选 + 真分页，固定按置顶权重倒序")
    public BaseResponse<Page<ProjectFrontVO>> listFrontProjects(@Valid ProjectQueryRequest projectQueryRequest) {
        // ① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传；
        //    @Valid 只负责标题关键词长度（@Size max=64），超长返回 A0401
        // ②③ 排序参数被忽略、pageSize 有上限，两条规则都在 Service 里，Controller 不重复
        return ResultUtils.success(studioProjectService.listFrontProjects(projectQueryRequest));
    }

    /**
     * 官网项目详情（游客可访问）
     *
     * 与列表的区别只是返回正文，因此单独一个 VO，而不是给列表加「要不要正文」的开关——
     * 开关会让同一接口在不同参数下返回不同结构。参与成员用脱敏 VO，结构上没有账号 ID。
     *
     * @param id 项目 ID，必须大于 0
     * @return 详情（列表字段 + Markdown 正文 + 参与成员）；不存在抛 A0402
     */
    @GetMapping("/detail")
    @Operation(summary = "官网项目详情", description = "匿名可访问；返回含 Markdown 正文的详情，不存在返回 A0402")
    public BaseResponse<ProjectFrontDetailVO> getFrontProjectById(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何项目，查库只会返回一个误导性的「项目不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // ③ 成员列表在 Service 里装配，用的是脱敏 VO，不含账号 ID 等内部字段
        return ResultUtils.success(studioProjectService.getFrontProjectById(id));
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 管理员新增项目
     *
     * 新增时在同一事务内把队长写进「成员-项目」关联表，保证队长必然出现在参与成员列表里。
     * 这一步用幂等的「保证成员在项目里」，而不是会报冲突的显式绑定接口。
     *
     * @param projectAddRequest title、description、leaderId 必填，其余选填
     * @return 新项目 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增项目", description = "队长必须存在；同事务同步队长到参与成员列表")
    public BaseResponse<Long> addProject(@RequestBody @Valid ProjectAddRequest projectAddRequest) {
        // ① @Valid 只保证字段级约束被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(projectAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 主表写与队长同步在同一个事务里，要么都成功要么都回滚
        return ResultUtils.success(studioProjectService.addProject(projectAddRequest));
    }

    /**
     * 管理员更新项目（部分更新：字段为 null 表示不修改）
     *
     * 换队长只把新队长写进关联表、不移除旧队长——卸任不等于退出项目，自动踢人等于用代码
     * 替管理员做业务决定。更新字段是服务端写死的白名单，不做 DTO 转实体整体 updateById。
     *
     * @param projectUpdateRequest id 必填，其余为 null 表示保持原值；传 leaderId 即换队长
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新项目", description = "null 表示不修改；换队长时同事务同步新队长，旧队长保留")
    public BaseResponse<Boolean> updateProject(@RequestBody @Valid ProjectUpdateRequest projectUpdateRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 「旧队长保留」是刻意的：卸任不等于退出项目，是否移除由管理员显式决定
        return ResultUtils.success(studioProjectService.updateProject(projectUpdateRequest));
    }

    /**
     * 管理员删除项目（逻辑删除）
     *
     * 顺序是「先清关联、再删主表」，且两次写同一事务；反过来一旦清理失败就会留下悬空关联。
     * 主表逻辑删除保留可追溯性，关联表物理删除——「谁参与了一个已不存在的项目」没有价值。
     *
     * @param deleteRequest id 必填且为正整数
     * @return true 只代表 UPDATE 命中了行，数据并没有从库里消失
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除项目", description = "逻辑删除；同事务清理成员-项目关联，数据可追溯")
    public BaseResponse<Boolean> deleteProject(@RequestBody @Valid DeleteRequest deleteRequest) {
        // ① 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 清关联与删主表在同一事务里，顺序反了会留下悬空关联
        return ResultUtils.success(studioProjectService.deleteProject(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询项目详情
     *
     * 查不到时抛 A0402 而不是返回 null。这个 VO 带正文大字段，只能用于单条详情，
     * 拿它当列表元素会让每一行都回传整篇 Markdown。
     *
     * @param id 项目 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return ProjectVO（含正文、队长、置顶权重与首页排名）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询项目详情")
    public BaseResponse<ProjectVO> getProjectById(@RequestParam("id") long id) {
        // ① 用 long 接必填的 id：缺参由 Spring 抛异常，非数字会被兜成 B0001：它们不可能对应任何项目，查库只会返回一个误导性的「项目不存在」
        // ② 先挡 0 与负数
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // ③ 管理端 VO 含正文与内部字段，因此不能把它复用到任何匿名接口上
        return ResultUtils.success(studioProjectService.getProjectById(id));
    }

    /**
     * 管理员分页查询项目
     *
     * 分页越界兜底纠正，但状态是闭集，非法值必须报 A0401——静默返回空列表会把排查方向带偏。
     * 排序字段走白名单：ORDER BY 无法用占位符参数化，直接拼前端字符串就是注入入口。
     *
     * @param projectQueryRequest title 模糊，status、leaderId 精确 + 分页与排序；允许整体为 null
     * @return 分页结果（ProjectVO）；无数据时 records 是空列表
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询项目", description = "支持标题模糊、状态与队长精确筛选，每页最多 50 条")
    public BaseResponse<Page<ProjectVO>> listProjectByPage(@Valid ProjectQueryRequest projectQueryRequest) {
        // ① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传；
        //    @Valid 只负责标题关键词长度（@Size max=64），超长返回 A0401
        // ②③ 默认排序与官网轮播口径一致，因此「后台列表顺序 = 首页顺序」
        return ResultUtils.success(studioProjectService.listProjectByPage(projectQueryRequest));
    }
}

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
 * Controller 只做三件事——接参、调 Service、包成 BaseResponse；
 * 校验与业务规则全在 Service 层。
 *
 * 两组接口的界线（本类最重要的约定）：
 * - C 端：/project/list、/project/detail —— 匿名可访问，两条都作为精确路径
 * 登记在 SaTokenMvcConfig 的白名单里，官网游客必须能看到项目案例页；
 * - 管理端：/project/add、/project/update、/project/delete、/project/get、
 * /project/list/page —— 全部要求 {@code @SaCheckRole} 为 admin。
 *
 * 两个容易看混的路径：/project/list（C 端，匿名，只返回脱敏列表）
 * 与 /project/list/page（管理端，要求 admin，返回全字段）。
 * 白名单里只登记前者，且必须写精确路径——写成 /project/** 或 /project/list*
 * 会把管理端接口一起放行，因为两者只差一个后缀。
 *
 * 返回的 VO 分三档：
 * - ProjectFrontVO：C 端列表项，不含正文与内部字段；
 * - ProjectFrontDetailVO：C 端详情，= 列表字段 + 正文 + 参与成员；
 * - ProjectVO：管理端视图，含正文、队长、置顶权重与首页排名。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/project/list。
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
     * <p>为什么这样设计：筛选条件是标题模糊与状态精确，排序固定为
     * 「置顶权重倒序 → 创建时间倒序 → id 倒序」。请求里的 sortField / sortOrder
     * 在这里被忽略——官网顺序属于产品定义，不能由请求方决定；
     * 允许匿名请求指定排序，等于允许任何人把某个项目顶到官网第一位。
     * 返回的 ProjectFrontVO 结构上没有正文：它是 Markdown 大字段，
     * 列表带上会让每一行都回传整篇正文，只有详情接口才取。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传；</li>
     *     <li>② 交给 Service：校验状态取值（闭集，非法值报 A0401 而不是静默返回空列表）、
     *     拼筛选条件、固定排序、分页查询并转脱敏 VO；</li>
     *     <li>③ 包成统一响应体。pageSize 在 Service 里有硬上限 50——
     *     这是匿名接口，无上限等于把「拉全表」的能力开放给任何人不限次数使用。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有鉴权注解，这是刻意设计。路径在白名单里，
     * 游客进项目案例页就该直接看到数据。但删掉白名单里那一行，这个接口会立刻
     * 变成「要求登录」，而代码上完全看不出变化——白名单与 Controller 是两处需要同步的地方。
     *
     * @param projectQueryRequest 查询条件：title 模糊、status 精确、分页；允许整体为 null
     * @return 分页结果，记录为 ProjectFrontVO（不含正文与内部字段）；
     * 没有项目时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException status 不在枚举内时抛出（A0401）；
     * 本接口不校验登录，因此不会抛 A0201
     */
    @GetMapping("/list")
    @Operation(summary = "官网项目列表", description = "匿名可访问；支持标题模糊与状态筛选 + 真分页，固定按置顶权重倒序")
    public BaseResponse<Page<ProjectFrontVO>> listFrontProjects(ProjectQueryRequest projectQueryRequest) {
        // ① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传
        // ②③ 排序参数被忽略、pageSize 有上限，两条规则都在 Service 里，Controller 不重复
        return ResultUtils.success(studioProjectService.listFrontProjects(projectQueryRequest));
    }

    /**
     * 官网项目详情（游客可访问）
     *
     * <p>为什么这样设计：与列表的唯一区别是返回正文——所以详情单独一个 VO，
     * 而不是让列表带一个「要不要正文」的开关。开关式设计会让同一个接口
     * 在不同参数下返回结构不同的数据，前端类型定义、缓存、监控都会跟着变复杂。
     * 详情还要装配「参与成员」，这是本项目唯一对外暴露成员信息的出口
     * （产品确认保留的例外），成员用脱敏 VO，结构上没有账号 ID。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何项目，直接给 A0401；</li>
     *     <li>③ 调 Service：查项目本体 → 转详情 VO（含正文）→ 装配参与成员。
     *     装配排在 VO 转换之后，因为成员列表是挂在 VO 上的字段。</li>
     * </ol>
     *
     * <p>容易踩的坑：装配成员是固定 2 次查询，不是 N+1。若有人把它改成
     * 「循环里查每个成员的详情」，这个接口的耗时就会随成员数线性增长——
     * 而官网项目详情是访问量最高的页面之一。
     *
     * @param id 项目 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 脱敏详情（列表字段 + Markdown 正文 + 参与成员列表）；
     * 项目不存在时抛 A0402，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException id 不合法（A0401）、
     * 项目不存在（A0402）时抛出；本接口不校验登录，因此不会抛 A0201
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
     * <p>为什么这样设计：新增时会在同一事务内把队长写进「成员-项目」关联表，
     * 保证「队长必然出现在参与成员列表中」。这一步用的是幂等的
     * 「保证成员在项目里」，而不是管理员的显式绑定接口——
     * 后者在冲突时会报错，而这里重复保存是正常路径。
     * 事务边界在 Service 的实现方法上，不在接口上：
     * Spring 的事务是运行期由代理织入的，注解放在接口上对基于类代理的配置不生效。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（标题、摘要、队长必填），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 交给 Service：校验必填项与状态取值 → 落库主表 → 把队长写进关联表。
     *     第三步必须排在落库之后，因为关联表要写项目 ID，而这个 ID 是插入那一刻才生成的；</li>
     *     <li>③ 返回新项目 ID（雪花算法生成的 19 位长整型，接口层序列化成字符串防前端丢精度）。</li>
     * </ol>
     *
     * <p>容易踩的坑：技术栈在数据库里存的是 JSON 快照字符串，接口对外是列表。
     * 序列化后超过列宽（256）必须报 A0401，否则会撞数据库的字符串截断错误——
     * 那种错误落到全局兜底就是 B0001，管理员只会看到「系统错误」，
     * 根本想不到是自己多填了两个标签。
     *
     * @param projectAddRequest 新增请求：title、description、leaderId 必填；
     *                          封面、正文、技术栈、链接、状态、权重选填
     * @return 新项目 ID（19 位，JSON 里是字符串）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法或技术栈超长（A0401）、队长不存在（A0402）时抛出
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
     * <p>为什么这样设计：换队长时只把新队长写进关联表，不移除旧队长——
     * 卸任不等于退出项目，他大概率仍在参与。自动踢人等于用代码替管理员做业务决定；
     * 真要移除由管理员走解绑接口，而解绑守卫保证「当前队长不可被移除」。
     * 更新字段是服务端写死的白名单，不做「DTO 转实体整体 updateById」，
     * 否则前端能顺手改 deletedAt 与审计字段。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：先查一次确认项目存在（updateById 失败只返回 false，
     *     前端分不清「不存在」与「没改成」），再校验传了的字段并写库；</li>
     *     <li>③ 返回布尔值。true 表示更新命中了一行，失败会抛异常而不是返回 false。</li>
     * </ol>
     *
     * <p>容易踩的坑：技术栈传空数组时要序列化成 "[]" 而不是 null。
     * 转成 null 会被 updateById 跳过，结果是「清空技术栈」永远不生效且不报错——
     * 管理员会以为按钮坏了，反复点，而数据库里的旧标签一直还在。
     *
     * @param projectUpdateRequest 更新请求：id 必填；其余字段为 null 表示保持原值，
     *                             传了 leaderId 即换队长
     * @return true 表示更新成功（失败抛异常，不会返回 false）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法或技术栈超长（A0401）、项目或队长不存在（A0402）时抛出
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
     * <p>为什么这样设计：顺序是「先清关联、再删主表」，且两次写必须在同一事务内。
     * 反过来写的话，主表删掉、清理失败就会留下指向已不存在项目的悬空关联；
     * 而现在的顺序下，第二步失败会让整个事务回滚，不会出现
     * 「项目还在、成员关联却没了」的静默数据丢失。
     * 主表用逻辑删除保留可追溯性，关联表用物理删除——
     * 「谁参与了一个已不存在的项目」没有保留价值，留着还会占住唯一索引。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（只判请求体：id 上的 @NotNull 由 {@code @Valid} 负责）；</li>
     *     <li>② 交给 Service：查存在性 → 清关联表 → 逻辑删除主表；</li>
     *     <li>③ 返回布尔值。true 只代表 UPDATE 命中了行，数据并没有从库里消失。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里 import 的是通用 DTO {@code model.dto.common.DeleteRequest}。
     * 帖子模块另有一个同名的 DeleteRequest，两者字段相同但语义不同，
     * 换错 import 编译器不会报错。另外不要在业务代码里改成物理删除——
     * 那会让「误删可恢复」这个前提消失。
     *
     * @param deleteRequest 删除请求：id 必填且为正整数
     * @return true 表示删除成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数不合法（A0401）、项目不存在（A0402）时抛出
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
     * <p>为什么这样设计：查不到时 Service 抛 A0402 而不是返回 null。
     * 返回 null 会让前端收到「code 为成功但 data 为空」的响应，
     * 它分不清是「确实没有这个项目」还是「接口出错了」，只能靠猜决定展示空态还是报错。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 id（基本类型 long，缺参由 Spring 抛异常）；</li>
     *     <li>② 挡一次明显的非法值：0 与负数不可能对应任何项目，直接给 A0401；</li>
     *     <li>③ 调 Service 取管理端 VO 并包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个 VO 带正文大字段，只能用于单条详情。拿它当列表元素
     * 会让每一行都回传整篇 Markdown——列表接口之所以单独一个 VO，正是这个原因。
     * 另外这里用 long 接参：传非数字（例如 ?id=abc）会被兜成 B0001 而不是 A0401。
     *
     * @param id 项目 ID，必须大于 0；传 0 或负数得到 A0401
     * @return 项目信息，记录为 ProjectVO（含正文、队长、置顶权重与首页排名）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * id 不合法（A0401）、项目不存在（A0402）时抛出
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
     * <p>为什么这样设计：分页参数做兜底纠正而不报错——页码传错没必要让整个查询失败；
     * 但状态是闭集，非法值必须报 A0401，静默返回空列表会把排查方向带偏
     * （管理员会以为「这个状态下一个项目都没有」）。
     * 排序字段走白名单：ORDER BY 的位置无法用占位符参数化，
     * 把前端字符串直接拼进去就是 SQL 注入入口。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传；</li>
     *     <li>② 交给 Service：校验状态取值、拼筛选条件、应用白名单排序、
     *     分页查询并转 VO（转换时补「首页轮播排名」，运营据此核对首页顺序）；</li>
     *     <li>③ 包成统一响应体。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法不加 {@code @Valid}。查询参数走 GET，校验失败抛的是
     * BindException，全局异常处理器没接它，会变成 B0001「系统错误」；
     * 而分页越界与排序字段非法都应该被纠正而不是报错。真正的闭集校验在 Service 里。
     *
     * @param projectQueryRequest 查询条件：title 模糊、status 精确、leaderId 精确、分页、排序；
     *                            允许整体为 null
     * @return 分页结果，记录为 ProjectVO（含正文与内部字段）；无数据时 records 是空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * status 不在枚举内（A0401）时抛出
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询项目", description = "支持标题模糊、状态与队长精确筛选，每页最多 50 条")
    public BaseResponse<Page<ProjectVO>> listProjectByPage(ProjectQueryRequest projectQueryRequest) {
        // ① Spring 把查询串绑定成 ProjectQueryRequest，允许一个参数都不传
        // ②③ 默认排序与官网轮播口径一致，因此「后台列表顺序 = 首页顺序」
        return ResultUtils.success(studioProjectService.listProjectByPage(projectQueryRequest));
    }
}

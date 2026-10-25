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
 * 这里维护「谁参与了这个项目」。注意它不是队长的权威源——
 * 队长由 studio_project.leader_id 决定（见 ProjectController），
 * 新增 / 修改项目时由 Service 在同一事务内把队长同步到本表，
 * 保证「队长必然出现在参与成员列表中」。
 *
 * 因此本类有两个必须说明的接口行为：
 * - /bind 是管理员显式添加：重复绑定返回 A0401，冲突必须被看见；
 * - /unbind 会拦住「移除当前队长」（A0401），否则会打破上面那个不变量。
 * 要换队长请先走 /project/update。
 *
 * 鉴权：四个接口全部要求 admin。关联关系是后台维护的数据，没有 C 端读者——
 * 官网的「项目参与成员」走的是项目详情接口（ProjectController 的 /project/detail），
 * 由 Service 装配脱敏后的成员 VO，与本类的管理端视图是两条路径。
 *
 * 本模块没有 C 端接口，也不在白名单里登记任何路径。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/member-project/bind。
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
     * <p>为什么这样设计：显式动作必须「所见即所得」——点了绑定，要么成功，
     * 要么明确告诉管理员为什么没成，所以重复绑定返回 A0401 而不是静默返回。
     * 这一点与项目模块内部用的「保证队长在成员列表里」是两种语义：
     * 那个是幂等的内部同步（重复执行是正常路径），这个是管理员的显式动作（冲突必须被看见）。
     * 若把换队长也走这个接口，管理员每次重复保存项目都会收到「该成员已参与此项目」，
     * 看起来像 bug，实际是两种语义被混用了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空。{@code @Valid} 只校验字段级约束（两个 id 非空且为正），
     *     请求体整体为 null 时它不触发；</li>
     *     <li>② 把两个 id 拆开传给 Service，而不是把整个 DTO 传进去；</li>
     *     <li>③ 返回布尔值。true 表示绑定成功，冲突与不存在都走异常。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法的 DTO 与「成员-证书」模块的绑定请求长得很像
     * （都是两个 id），但项目 id 与成员 id 的顺序不同、含义也不同。
     * 前端若把两个参数写反，接口不会报错——它只会安静地建立一条错误的关联。
     *
     * @param projectMemberRequest 绑定请求：projectId 与 memberId 均必填且为正整数
     * @return true 表示绑定成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法或该成员已参与此项目（A0401）、项目或成员不存在（A0402）时抛出
     */
    @PostMapping("/bind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】绑定成员到项目", description = "项目与成员都必须存在；重复绑定返回 A0401")
    public BaseResponse<Boolean> bindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 显式动作必须报冲突：与内部同步用的「保证在里面」是两种语义，不能混用
        return ResultUtils.success(memberProjectService.bindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 解绑成员（物理删除关联行）
     *
     * <p>为什么这样设计：项目表存 leader_id，关联表存成员名单，两者必须一致。
     * 若允许把队长从关联表里删掉，就会出现「项目有队长、但成员列表里没有他」
     * 的破窗状态——官网项目页看不到负责人，而项目本身确实有负责人。
     * 所以 Service 会读 leader_id 做守卫，命中就返回 A0401 并提示「请先更换队长」。
     * 关联表用物理删除，因为它没有 deleted_at 列：若改成逻辑删除，
     * 同一对（项目, 成员）会与被标记删除的行冲突，重复绑定就再也插不进去了。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 兜底判空（{@code @Valid} 对整体为 null 的请求体不生效）；</li>
     *     <li>② 交给 Service：先确认关联存在（不存在给 A0402），再读项目表做队长守卫，
     *     最后物理删除。顺序不能换——先删再守卫，队长就已经被移除了；</li>
     *     <li>③ 返回布尔值，true 表示解绑成功。</li>
     * </ol>
     *
     * <p>容易踩的坑：守卫读的必须是 studio_project.leader_id（权威源），
     * 而不是关联表里的某个标记位。多一份需要同步的冗余状态，
     * 就多一次两边不一致、守卫失效的机会。
     *
     * @param projectMemberRequest 解绑请求：projectId 与 memberId 均必填且为正整数
     * @return true 表示解绑成功
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * 参数非法或该成员是当前队长（A0401）、关联不存在（A0402）时抛出
     */
    @PostMapping("/unbind")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】解绑项目成员", description = "未参与返回 A0402；当前队长不可解绑（A0401）")
    public BaseResponse<Boolean> unbindMember(@RequestBody @Valid ProjectMemberRequest projectMemberRequest) {
        // ① 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(projectMemberRequest == null, ErrorCode.PARAMS_ERROR);
        // ②③ 队长守卫在 Service 里读 studio_project.leader_id，Controller 不重复判
        return ResultUtils.success(memberProjectService.unbindMember(
                projectMemberRequest.getProjectId(), projectMemberRequest.getMemberId()));
    }

    /**
     * 查参与某项目的全部成员
     *
     * <p>为什么这样设计：参数用包装类型 Long 而不是基本类型 long，
     * 这是本方法最需要讲清的一点。「没传 projectId」在基本类型下会抛
     * MissingServletRequestParameterException，全局异常处理器没有为它写处理器，
     * 于是被兜成 B0001「系统错误」；用 Long + 显式判空才能给出
     * 「项目 id 不能为空」这种准确的 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 projectId（包装类型，缺参时为 null 而不是抛异常）；</li>
     *     <li>② 显式判空并给可读文案；</li>
     *     <li>③ 调 Service：先校验项目存在（不存在给 A0402），再两步查询取成员。
     *     顺序不能反——项目 ID 根本不存在时也返回空列表，前端会理解成
     *     「这个项目还没有成员」，而实际上项目本身不存在。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回结果里必然包含队长（同步不变量），前端不要再去单独取一次队长，
     * 否则同一个人会在页面上出现两次。另外返回值是管理端 VO，
     * 含 userId 等内部字段，不能直接拿去渲染官网页面。
     *
     * @param projectId 项目 ID，必填且必须大于 0；缺参时得到 A0401
     * @return 成员列表，记录为 MemberVO（含账号绑定与置顶权重），按置顶权重 → id 倒序；
     * 项目没有成员时返回空列表（但队长必然在里面）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * projectId 缺失（A0401）、项目不存在（A0402）时抛出
     */
    @GetMapping("/member/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查项目的参与成员", description = "项目不存在返回 A0402；队长也在列表中")
    public BaseResponse<List<MemberVO>> listMembersByProject(@RequestParam("projectId") Long projectId) {
        // ① 用包装类型 Long 接收：缺参时为 null，而不是由 Spring 抛异常：缺参时给 A0401「项目 id 不能为空」，而不是 Spring 的 B0001
        // ② 用包装类型 + 显式判空
        ThrowUtils.throwIf(projectId == null, ErrorCode.PARAMS_ERROR, "项目 id 不能为空");
        // ③ 先校验项目存在再查成员：顺序反了会把「项目不存在」伪装成「还没有成员」
        return ResultUtils.success(memberProjectService.listMembersByProject(projectId));
    }

    /**
     * 查某成员参与的全部项目（反向查询）
     *
     * <p>为什么这样设计：入参名用 memberId 而不是复用 projectId。
     * 两个接口的参数含义相反，名字写清楚才不会被前端调错——
     * 而调错的后果不是报错，是「查出来一堆看似合理的数据」：
     * 拿项目 ID 当成员 ID 去查，通常会得到一个空列表，前端只会以为「这人没参与任何项目」。
     * 反向查询走关联表上的另一侧索引，不必为它新建索引。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 接收必填的 memberId（包装类型，缺参时为 null）；</li>
     *     <li>② 显式判空并给可读文案；</li>
     *     <li>③ 调 Service：先校验成员存在，再两步查询取项目并转成管理端 VO。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里返回的 ProjectVO 带 content 正文大字段，返回条数等于
     * 该成员参与的项目数。数量少时没问题，一旦某人参与几十个项目，
     * 这个接口的响应体就会明显变大——那种情况下应该换成不含正文的列表 VO。
     *
     * @param memberId 成员 ID，必填且必须大于 0；缺参时得到 A0401
     * @return 项目列表，记录为 ProjectVO（含正文与队长等内部字段），
     * 按置顶权重 → 创建时间 → id 倒序；没有参与项目时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录或角色不足（A0201 / A0301）、
     * memberId 缺失（A0401）、成员不存在（A0402）时抛出
     */
    @GetMapping("/project/list")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查成员参与的项目", description = "成员不存在返回 A0402")
    public BaseResponse<List<ProjectVO>> listProjectsByMember(@RequestParam("memberId") Long memberId) {
        // ① 用包装类型 Long 接收：缺参时为 null，而不是由 Spring 抛异常：包装类型接参，缺参时由这里给出准确的 A0401
        // ② 与上一个接口同一套写法
        ThrowUtils.throwIf(memberId == null, ErrorCode.PARAMS_ERROR, "成员 id 不能为空");
        // ③ 先校验成员存在再查项目：否则「成员不存在」会被伪装成「这人没有项目」
        return ResultUtils.success(memberProjectService.listProjectsByMember(memberId));
    }
}

package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.vo.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.MemberVO;
import com.bhu.runshistudioweb.service.StudioMemberService;
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
 * 成员接口：C 端（官网展示）+ 管理端（管理员增删改查）
 *
 * author: shaoshing
 *
 * <p>Controller 的职责边界与 {@link UserController} 一致：只做三件事——接参、调 Service、
 * 包成 {@link BaseResponse}；业务规则全部在 Service 层，这里不写 if-else 业务分支。
 *
 * <p>两组接口的界线（本类最重要的约定）：
 * <table border="1">
 *     <caption>接口分组</caption>
 *     <tr><th>分组</th><th>路径</th><th>鉴权</th></tr>
 *     <tr><td>C 端</td><td>{@code /member/list}</td>
 *     <td><b>匿名可访问</b>——已加入 Sa-Token 白名单（见 SaTokenMvcConfig），官网游客必须能看成员页</td></tr>
 *     <tr><td>管理端</td><td>{@code /member/add}、{@code /member/update}、{@code /member/delete}、
 *     {@code /member/get}、{@code /member/list/page}</td>
 *     <td>全部要求 {@code @SaCheckRole("admin")}</td></tr>
 * </table>
 *
 * <p><b>返回类型的分界是安全设计，不是风格问题</b>：C 端返回 {@link MemberFrontVO}（公开字段白名单），
 * 管理端返回 {@link MemberVO}（含 userId / sortOrder 等内部字段）。
 * 两组绝不能互换——把管理端 VO 用到公开接口上等于把账号绑定关系公开。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，完整路径形如
 * {@code http://localhost:8080/api/member/list}。
 */
@RestController
@RequestMapping("/member")
@Tag(name = "成员模块", description = "官网成员展示（匿名可访问）与管理员成员管理")
public class StudioMemberController {

    /**
     * 用 @Resource 而不是 @Autowired：按名称注入，装配失败时的报错信息更直观
     */
    @Resource
    private StudioMemberService studioMemberService;

    // ==================== C 端：官网展示，匿名可访问 ====================

    /**
     * 官网成员列表（游客可访问）
     *
     * <p>固定按置顶权重（sort_order）倒序返回；请求里的分页与排序参数会被忽略——
     * 公开接口的排序规则由产品定义，不接受任意排序字段。
     *
     * @param memberQueryRequest 查询条件（可选：id/姓名/届别/方向/职务/状态），允许为空
     * @return 脱敏后的成员列表
     */
    @GetMapping("/list")
    @Operation(summary = "官网成员列表", description = "匿名可访问；支持按届别/方向/职务/状态筛选，固定按置顶权重倒序")
    public BaseResponse<List<MemberFrontVO>> listFrontMembers(MemberQueryRequest memberQueryRequest) {
        return ResultUtils.success(studioMemberService.listFrontMembers(memberQueryRequest));
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 管理员新增成员档案
     *
     * @param memberAddRequest 新增请求（姓名与入学年份必填；userId 为可选的账号绑定）
     * @return 新成员 ID（雪花算法生成的 Long，序列化时已是字符串，前端不会精度丢失）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增成员", description = "可绑定系统账号；绑定校验「账号存在且未被占用」")
    public BaseResponse<Long> addMember(@RequestBody @Valid MemberAddRequest memberAddRequest) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(memberAddRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioMemberService.addMember(memberAddRequest));
    }

    /**
     * 管理员更新成员档案
     *
     * <p>部分更新语义：请求体里为 null 的字段不会被改动，前端「只改头像」时不必回传整行数据。
     *
     * @param memberUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新成员", description = "字段为 null 表示不修改；改绑账号时校验唯一性")
    public BaseResponse<Boolean> updateMember(@RequestBody @Valid MemberUpdateRequest memberUpdateRequest) {
        ThrowUtils.throwIf(memberUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioMemberService.updateMember(memberUpdateRequest));
    }

    /**
     * 管理员删除成员档案（逻辑删除）
     *
     * @param deleteRequest 删除请求（id 必填）
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除成员", description = "逻辑删除，数据可追溯；其绑定的账号会被释放")
    public BaseResponse<Boolean> deleteMember(@RequestBody @Valid DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(studioMemberService.deleteMember(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询成员详情
     *
     * @param id 成员 ID
     * @return 含内部字段的成员信息（管理端视图）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询成员详情")
    public BaseResponse<MemberVO> getMemberById(@RequestParam("id") long id) {
        // 用 long 接参：非数字字符串会由全局异常处理器兜成 40000；这里再挡一次明显的非法值
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        return ResultUtils.success(studioMemberService.getMemberById(id));
    }

    /**
     * 管理员分页查询成员列表
     *
     * <p>查询参数走 GET（便于分享链接与浏览器缓存），没有请求体；
     * 分页与排序的非法值由 Service 兜底纠正，默认按置顶权重（sort_order）倒序。
     *
     * @param memberQueryRequest 查询条件（id/姓名/届别/方向/职务/状态
     *                            + current/pageSize/sortField/sortOrder），允许为空
     * @return 分页结果，记录为含内部字段的 {@link MemberVO}
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询成员", description = "支持姓名/方向模糊查询与届别筛选，每页最多 50 条")
    public BaseResponse<Page<MemberVO>> listMemberByPage(MemberQueryRequest memberQueryRequest) {
        return ResultUtils.success(studioMemberService.listMemberByPage(memberQueryRequest));
    }
}
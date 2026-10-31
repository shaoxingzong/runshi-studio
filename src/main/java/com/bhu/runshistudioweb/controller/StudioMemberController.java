package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberAddRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberQueryRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberSelfUpdateRequest;
import com.bhu.runshistudioweb.model.dto.member.MemberUpdateRequest;
import com.bhu.runshistudioweb.model.entity.StudioMember;
import com.bhu.runshistudioweb.model.vo.member.MemberFrontVO;
import com.bhu.runshistudioweb.model.vo.member.MemberVO;
import com.bhu.runshistudioweb.service.StudioMemberService;
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
 * 成员接口：管理端增删改查 + 一个「我的档案」查询
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/member")
@Tag(name = "成员模块", description = "官网成员展示（匿名可访问）与管理员成员管理")
@RequiredArgsConstructor
public class StudioMemberController {

    /** 构造器注入：依赖装配后不可变，缺失在启动期就失败，而不是调用时才 NPE */
    private final StudioMemberService studioMemberService;

    // 当前登录用户：查自己的在队成员档案

    /**
     * 我的成员档案（当前登录用户）
     *
     * @return 在队档案（脱敏视图）；不是成员或已离队时为 null，前端必须判空
     */
    @GetMapping("/mine")
    @SaCheckLogin
    @Operation(summary = "【登录用户】我的成员档案", description = "在队则返回档案；不是成员返回 null")
    public BaseResponse<MemberFrontVO> mine() {
        // 从登录态取当前账号 ID；未登录走不到这里，@SaCheckLogin 会先拦下
        long userId = StpUtil.getLoginIdAsLong();
        // 与考勤、刷题同一套判定：查在队成员，认表不认角色
        StudioMember member = studioMemberService.getActiveMemberByUserId(userId);
        // 脱敏后返回：不含 userId / sortOrder / 审计字段（MemberFrontVO 本身就是白名单）
        return ResultUtils.success(member == null ? null : studioMemberService.getMemberFrontVO(member));
    }

    /**
     * 成员自助编辑本人档案（只开放展示字段）
     *
     * @param request 姓名/头像/专业/技术方向/GitHub/简介；null 字段表示不修改
     * @return 更新后的脱敏档案，供前端立即刷新展示
     */
    @PostMapping("/mine/update")
    @SaCheckLogin
    @Operation(summary = "【登录成员】编辑我的成员资料",
            description = "仅可修改姓名、头像、专业、技术方向、GitHub、简介；登录账号、届别、职务、状态、排序仅管理员可改")
    public BaseResponse<MemberFrontVO> updateMine(@RequestBody @Valid MemberSelfUpdateRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        // userId 从登录态取，请求体不携带账号标识——成员只能改自己的档案
        long userId = StpUtil.getLoginIdAsLong();
        return ResultUtils.success(studioMemberService.updateMyProfile(userId, request));
    }

    // 管理端：全部要求 admin 角色

    /**
     * 管理员新增成员档案
     *
     * @param memberAddRequest 姓名与入学年份必填；userId 为可选的账号绑定
     * @return 新成员 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增成员", description = "可绑定系统账号；绑定校验「账号存在且未被占用」")
    public BaseResponse<Long> addMember(@RequestBody @Valid MemberAddRequest memberAddRequest) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，这里兜一层防 NPE
        ThrowUtils.throwIf(memberAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 账号占用检查在 Service 里；并发下由唯一索引兜底，不靠应用层查询保证
        return ResultUtils.success(studioMemberService.addMember(memberAddRequest));
    }

    /**
     * 管理员更新成员档案
     *
     * @param memberUpdateRequest id 必填，其余字段为 null 表示保持原值
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新成员", description = "字段为 null 表示不修改；改绑账号时校验唯一性")
    public BaseResponse<Boolean> updateMember(@RequestBody @Valid MemberUpdateRequest memberUpdateRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(memberUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 「排除自身的唯一性校验」是这里最容易漏的一步，它在 Service 里
        return ResultUtils.success(studioMemberService.updateMember(memberUpdateRequest));
    }

    /**
     * 管理员删除成员档案（逻辑删除）
     *
     * @param deleteRequest id 必填且为正整数
     * @return true 只代表 UPDATE 命中了行
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除成员", description = "逻辑删除，数据可追溯；其绑定的账号会被释放")
    public BaseResponse<Boolean> deleteMember(@RequestBody @Valid DeleteRequest deleteRequest) {
        // 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 级联清理在 Service 的同一事务里完成；账号释放靠「唯一索引带删除标记」实现
        return ResultUtils.success(studioMemberService.deleteMember(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询成员详情
     *
     * 查不到时抛 A0402 而不是返回 null，否则前端分不清「确实没有这个成员」还是「接口出错」。
     *
     * @param id 成员 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return MemberVO（含 userId、sortOrder 与审计字段）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询成员详情")
    public BaseResponse<MemberVO> getMemberById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // 管理端 VO 含内部字段，因此不能把它复用到任何对外接口上
        return ResultUtils.success(studioMemberService.getMemberById(id));
    }

    /**
     * 管理员分页查询成员列表
     *
     * @param memberQueryRequest 姓名与方向模糊，届别、职务、状态精确 + 分页与排序；允许为 null
     * @return 分页结果（MemberVO，含内部字段）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询成员", description = "支持姓名/方向模糊查询与届别筛选，每页最多 50 条")
    public BaseResponse<Page<MemberVO>> listMemberByPage(@Valid MemberQueryRequest memberQueryRequest) {
        return ResultUtils.success(studioMemberService.listMemberByPage(memberQueryRequest));
    }
}

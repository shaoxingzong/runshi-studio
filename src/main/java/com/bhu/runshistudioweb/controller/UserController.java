package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.constant.UserRoleConstant;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.common.DeleteRequest;
import com.bhu.runshistudioweb.model.dto.user.UserAddRequest;
import com.bhu.runshistudioweb.model.dto.user.UserLoginRequest;
import com.bhu.runshistudioweb.model.dto.user.UserQueryRequest;
import com.bhu.runshistudioweb.model.dto.user.UserRegisterRequest;
import com.bhu.runshistudioweb.model.dto.user.UserUpdateRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.user.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.user.UserAddResultVO;
import com.bhu.runshistudioweb.model.vo.user.UserVO;
import com.bhu.runshistudioweb.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：C 端（用户自己）+ 管理端（管理员增删改查）
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户注册、登录、注销、当前用户查询，以及管理员用户管理")
@RequiredArgsConstructor
public class UserController {

    /** 构造器注入：依赖装配后不可变，缺失在启动期就失败，而不是调用时才 NPE */
    private final UserService userService;

    /** Nginx 惯用配置 proxy_set_header X-Real-IP $remote_addr; 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 客户端, 代理1, 代理2 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    // C 端：用户自己

    /**
     * 用户注册
     *
     * @param userRegisterRequest 账号、姓名、密码、确认密码均必填；request 只用于取 IP 三参
     * @return 新用户 ID（19 位，JSON 里是字符串）
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "校验账号唯一性、姓名与密码格式后创建账号；姓名用于站内展示，账号仅用于登录；按 IP 限流防批量注册")
    public BaseResponse<Long> userRegister(@RequestBody @Valid UserRegisterRequest userRegisterRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);

        // IP 三参必须在当前请求线程内取好：它们与 socket 层绑定，离开请求就取不到了
        long id = userService.userRegister(
                userRegisterRequest.getUserAccount(),
                userRegisterRequest.getUserName(),
                userRegisterRequest.getUserPassword(),
                userRegisterRequest.getCheckPassword(),
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR));
        // 只回 ID：注册成功不代表已登录，前端应当引导用户去登录页
        return ResultUtils.success(id);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 账号与密码均必填；request 只用于取 IP 三参
     * @return 脱敏后的登录用户信息（含 token，前端需要保存）
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "校验密码并下发 token（含 Cookie，见 sa-token 配置）；按 IP 限流防爆破")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody @Valid UserLoginRequest userLoginRequest,
                                               HttpServletRequest request) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);

        // 会话建立与密码比对都在 Service 里；IP 三参只用于限流判定
        LoginUserVO loginUserVO = userService.userLogin(
                userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword(),
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR));
        // token 在 VO 里，前端必须保存它；后续请求靠它识别身份
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     *
     * @return 当前登录用户的脱敏信息（不含 token，含角色与 AI 配额）
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前登录用户", description = "未登录时由全局异常处理器返回 A0201")
    public BaseResponse<LoginUserVO> getLoginUser() {
        // 未登录 / token 过期都会在这里抛异常，交给全局异常处理器转成 A0201
        SysUser loginUser = userService.getLoginUser();
        // ③ 返回 VO 而不是实体：实体里有密码哈希串，直接返回等于把密码送到前端
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销（退出登录）
     *
     * @return true 表示注销成功
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销", description = "清除服务端会话与客户端 Cookie")
    public BaseResponse<Boolean> userLogout() {
        // ② 未登录时 Service 会抛 A0201 而不是静默返回 true——含糊的成功会掩盖登录态问题
        return ResultUtils.success(userService.userLogout());
    }

    // 管理端：全部要求 admin 角色

    /**
     * 管理员新增用户
     *
     * @param userAddRequest 账号必填；密码留空则生成随机初始密码，可指定角色与状态
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增用户", description = "可指定角色与状态；密码留空时生成随机初始密码（仅创建响应返回一次）")
    public BaseResponse<UserAddResultVO> addUser(@RequestBody @Valid UserAddRequest userAddRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 随机初始密码只在这一次响应里出现，服务端只存哈希、无法再次取回
        return ResultUtils.success(userService.addUser(userAddRequest));
    }

    /**
     * 管理员更新用户
     *
     * @param userUpdateRequest id 必填，其余为 null 表示保持原值；可重置密码、调整角色与状态
     * @return true 表示更新成功，失败抛异常
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新用户", description = "字段为 null 表示不修改；可重置密码、调整角色与状态")
    public BaseResponse<Boolean> updateUser(@RequestBody @Valid UserUpdateRequest userUpdateRequest) {
        // 空 body 时 @Valid 不触发，先挡住再谈字段校验
        ThrowUtils.throwIf(userUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 「不允许把自己降级或封禁」的守卫在 Service 里，Controller 不重复判
        return ResultUtils.success(userService.updateUser(userUpdateRequest));
    }

    /**
     * 管理员删除用户（逻辑删除）
     *
     * @param deleteRequest id 必填且为正整数
     * @return true 只代表 UPDATE 命中了行
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除用户", description = "逻辑删除，数据可追溯；账号名可被重新注册")
    public BaseResponse<Boolean> deleteUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        // 只判请求体：id 字段上的 @NotNull 由 @Valid 负责，再判一遍就是重复校验
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        // ③ 「不能删自己」的守卫在 Service 里：没有它，管理员可能把自己锁在系统外
        return ResultUtils.success(userService.deleteUser(deleteRequest.getId()));
    }

    /**
     * 管理员重置某个用户的 AI 提问配额
     *
     * @param request id 必填且为正整数
     * @return 重置前的已用次数（含未回刷的增量），供前端提示「已从 N 次清零」
     */
    @PostMapping("/reset-ai-quota")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】重置用户的 AI 提问次数", description = "把已用次数清零；返回重置前的次数")
    public BaseResponse<Integer> resetAiQuota(@RequestBody @Valid DeleteRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.resetAiQueryCount(request.getId()));
    }

    /**
     * 管理员按 id 查询用户详情
     *
     * @param id 用户 ID，必须大于 0；缺参或非数字会落到 B0001
     * @return UserVO（含角色与状态，不含密码）
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询用户详情")
    public BaseResponse<UserVO> getUserById(@RequestParam("id") long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        // Service 返回的是 VO 而不是实体，密码哈希串没有出口
        return ResultUtils.success(userService.getUserById(id));
    }

    /**
     * 管理员分页查询用户列表
     *
     * @param userQueryRequest id 精确、账号与昵称模糊、角色与状态精确 + 分页与排序；允许为 null
     * @return 分页结果（UserVO，脱敏）
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询用户", description = "支持账号/昵称模糊查询，每页最多 50 条")
    public BaseResponse<Page<UserVO>> listUserByPage(@Valid UserQueryRequest userQueryRequest) {
        return ResultUtils.success(userService.listUserByPage(userQueryRequest));
    }
}

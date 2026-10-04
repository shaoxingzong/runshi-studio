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
import com.bhu.runshistudioweb.model.vo.LoginUserVO;
import com.bhu.runshistudioweb.model.vo.UserVO;
import com.bhu.runshistudioweb.service.UserService;
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
 * 用户接口：C 端（用户自己）+ 管理端（管理员增删改查）
 *
 * author: shaoshing
 *
 * <p>Controller 的职责边界：**只做三件事**——接参、调 Service、把结果包成 {@link BaseResponse}。
 * 参数长度、密码强度、业务规则全部在 Service 层，Controller 里不写 if-else 业务分支，
 * 这样同一套规则在别的入口（如管理端、定时任务）复用时不会有两份实现。
 *
 * <p>返回类型一律是 {@code BaseResponse<T>}：前端只需一个拦截器即可统一处理成败，
 * 业务错误码由全局异常处理器兜住（见 {@code GlobalExceptionHandler}），
 * 所以这里**不写 try-catch**，出了异常直接往外抛。
 *
 * <p>两组接口的界线（这是本类最重要的约定）：
 * <table border="1">
 *     <caption>接口分组</caption>
 *     <tr><th>分组</th><th>路径</th><th>鉴权</th></tr>
 *     <tr><td>C 端</td><td>{@code /user/register}、{@code /user/login}、
 *     {@code /user/current}、{@code /user/logout}</td>
 *     <td>前两个在 Sa-Token 白名单里（匿名可访问），后两个要求登录</td></tr>
 *     <tr><td>管理端</td><td>{@code /user/add}、{@code /user/update}、{@code /user/delete}、
 *     {@code /user/get}、{@code /user/list/page}</td>
 *     <td>全部要求 {@code @SaCheckRole("admin")}</td></tr>
 * </table>
 * 特别注意：C 端与管理端虽然都返回用户信息，但**返回的 VO 不同**——
 * 自己看自己用 {@link LoginUserVO}（带 token），管理员看别人用 {@link UserVO}（带角色与状态）。
 * 两者都不含密码字段。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，所以完整路径是
 * {@code http://localhost:8080/api/user/login}。
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户注册、登录、注销、当前用户查询，以及管理员用户管理")
public class UserController {

    /**
     * 用 @Resource 而不是 @Autowired：按名称注入，装配失败时的报错信息更直观
     */
    @Resource
    private UserService userService;

    // ==================== C 端：用户自己 ====================

    /**
     * 用户注册
     *
     * @param userRegisterRequest 注册请求体（账号、密码、确认密码）
     * @return 新注册用户的 ID（雪花算法生成的 Long，序列化时已是字符串，前端不会精度丢失）
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "校验账号唯一性与密码格式后创建账号，新账号默认为普通用户角色")
    public BaseResponse<Long> userRegister(@RequestBody @Valid UserRegisterRequest userRegisterRequest) {
        // @Valid 只保证「字段级约束」被校验，请求体整体为 null 时不会触发，
        // 因此这里兜一层，避免下面直接 getXxx() 抛 NPE 变成 500 系统错误
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);

        long id = userService.userRegister(
                userRegisterRequest.getUserAccount(),
                userRegisterRequest.getUserPassword(),
                userRegisterRequest.getCheckPassword());
        return ResultUtils.success(id);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 登录请求体（账号、密码）
     * @return 脱敏后的登录用户信息，其中 token 需要前端保存（请求头 satoken: xxx）
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "校验密码并下发 token（含 Cookie，见 sa-token 配置）")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody @Valid UserLoginRequest userLoginRequest) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);

        LoginUserVO loginUserVO = userService.userLogin(
                userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword());
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     *
     * <p>典型用途：前端刷新页面后，用它判断「是否还处于登录状态」并把用户信息写回状态管理，
     * 而不是把用户信息存 localStorage 里反复使用（那样改了昵称/头像前端不会更新）。
     *
     * @return 当前登录用户的脱敏信息（不含 token）
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前登录用户", description = "未登录时由全局异常处理器返回 A0201")
    public BaseResponse<LoginUserVO> getLoginUser() {
        // 未登录 / token 过期都会在这里抛异常，交给全局异常处理器转成 A0201
        SysUser loginUser = userService.getLoginUser();
        // 返回 VO 而不是 entity：entity 里有 userPassword 哈希串，直接返回等于把密码送到前端
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销（退出登录）
     *
     * <p>注销是幂等语义层面的「清理动作」：Service 里会先判断是否登录，
     * 未登录时返回 A0201，避免前端在 token 已过期的情况下调用本接口拿到令人困惑的结果。
     *
     * @return true 表示注销成功
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销", description = "清除服务端会话与客户端 Cookie")
    public BaseResponse<Boolean> userLogout() {
        return ResultUtils.success(userService.userLogout());
    }

    // ==================== 管理端：全部要求 admin 角色 ====================

    /**
     * 管理员新增用户
     *
     * @param userAddRequest 新增请求（账号必填；密码留空则使用默认初始密码）
     * @return 新用户 ID
     */
    @PostMapping("/add")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】新增用户", description = "可指定角色与状态；密码留空时使用默认初始密码")
    public BaseResponse<Long> addUser(@RequestBody @Valid UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.addUser(userAddRequest));
    }

    /**
     * 管理员更新用户
     *
     * <p>部分更新语义：请求体里为 null 的字段不会被改动，因此前端「只改昵称」时不必回传整行数据。
     *
     * @param userUpdateRequest 更新请求（id 必填）
     * @return true 表示更新成功
     */
    @PostMapping("/update")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】更新用户", description = "字段为 null 表示不修改；可重置密码、调整角色与状态")
    public BaseResponse<Boolean> updateUser(@RequestBody @Valid UserUpdateRequest userUpdateRequest) {
        ThrowUtils.throwIf(userUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.updateUser(userUpdateRequest));
    }

    /**
     * 管理员删除用户（逻辑删除）
     *
     * @param deleteRequest 删除请求（id 必填）
     * @return true 表示删除成功
     */
    @PostMapping("/delete")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】删除用户", description = "逻辑删除，数据可追溯；账号名可被重新注册")
    public BaseResponse<Boolean> deleteUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.deleteUser(deleteRequest.getId()));
    }

    /**
     * 管理员按 id 查询用户详情
     *
     * @param id 用户 ID
     * @return 脱敏后的用户信息
     */
    @GetMapping("/get")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】查询用户详情")
    public BaseResponse<UserVO> getUserById(@RequestParam("id") long id) {
        // 用 long 接参：如果传的是非数字字符串，Spring 会抛类型转换异常，
        // 由全局异常处理器兜成 A0401 而不是 500；这里再挡一次明显的非法值
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 不合法");
        return ResultUtils.success(userService.getUserById(id));
    }

    /**
     * 管理员分页查询用户列表
     *
     * <p>查询参数走 GET（便于分享链接与浏览器缓存），没有请求体；
     * 分页与排序的非法值由 Service 兜底纠正，不会因为「页码传成 0」而报错。
     *
     * @param userQueryRequest 查询条件（id/userAccount/userName/userRole/userStatus
     *                          + current/pageSize/sortField/sortOrder），允许为空
     * @return 分页结果，记录为脱敏后的 {@link UserVO}
     */
    @GetMapping("/list/page")
    @SaCheckRole(UserRoleConstant.ADMIN)
    @Operation(summary = "【管理员】分页查询用户", description = "支持账号/昵称模糊查询，每页最多 50 条")
    public BaseResponse<Page<UserVO>> listUserByPage(UserQueryRequest userQueryRequest) {
        return ResultUtils.success(userService.listUserByPage(userQueryRequest));
    }
}

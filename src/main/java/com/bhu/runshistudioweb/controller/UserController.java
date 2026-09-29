package com.bhu.runshistudioweb.controller;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.exception.ErrorCode;
import com.bhu.runshistudioweb.exception.ThrowUtils;
import com.bhu.runshistudioweb.model.dto.user.UserLoginRequest;
import com.bhu.runshistudioweb.model.dto.user.UserRegisterRequest;
import com.bhu.runshistudioweb.model.entity.SysUser;
import com.bhu.runshistudioweb.model.vo.LoginUserVO;
import com.bhu.runshistudioweb.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：注册、登录、注销、获取当前登录用户
 *
 * author: shaoshing
 *
 * <p>Controller 的职责边界：**只做三件事**——接参、调 Service、把结果包成 {@link BaseResponse}。
 * 参数长度、密码强度、业务规则全部在 Service 层，Controller 里不写 if-else 业务分支，
 * 这样同一套规则在别的入口（如后台管理、定时任务）复用时不会有两份实现。
 *
 * <p>返回类型一律是 {@code BaseResponse<T>}：前端只需一个拦截器即可统一处理成败，
 * 业务错误码由全局异常处理器兜住（见 {@code GlobalExceptionHandler}），
 * 所以这里**不写 try-catch**，出了异常直接往外抛。
 *
 * <p>关于请求方法：注册、登录、注销都必须用 <b>POST</b>。
 * 用 GET 会把账号密码写进 URL，从而留在浏览器历史、Nginx access log 和代理日志里，
 * 是最常见的密码泄露途径之一。
 *
 * <p>接口地址前缀：{@code server.servlet.context-path=/api}，所以完整路径是
 * {@code http://localhost:8080/api/user/login}。
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户注册、登录、注销与当前登录用户查询")
public class UserController {

    /**
     * 用 @Resource 而不是 @Autowired：按名称注入，装配失败时的报错信息更直观
     */
    @Resource
    private UserService userService;

    /**
     * 用户注册
     *
     * @param userRegisterRequest 注册请求体（账号、密码、确认密码）
     * @return 新注册用户的 ID（雪花算法生成的 Long，序列化时已是字符串，前端不会精度丢失）
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "校验账号唯一性与密码格式后创建账号，默认角色由 Service 决定")
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
    @Operation(summary = "获取当前登录用户", description = "未登录时由全局异常处理器返回 40100")
    public BaseResponse<LoginUserVO> getLoginUser() {
        // 未登录 / token 过期都会在这里抛异常，交给全局异常处理器转成 40100
        SysUser loginUser = userService.getLoginUser();
        // 返回 VO 而不是 entity：entity 里有 userPassword 哈希串，直接返回等于把密码送到前端
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销（退出登录）
     *
     * <p>注销是幂等语义层面的「清理动作」：Service 里会先判断是否登录，
     * 未登录时返回 40100，避免前端在 token 已过期的情况下调用本接口拿到令人困惑的结果。
     *
     * @return true 表示注销成功
     */
    @PostMapping("/logout")
    @Operation(summary = "用户注销", description = "清除服务端会话与客户端 Cookie")
    public BaseResponse<Boolean> userLogout() {
        return ResultUtils.success(userService.userLogout());
    }
}

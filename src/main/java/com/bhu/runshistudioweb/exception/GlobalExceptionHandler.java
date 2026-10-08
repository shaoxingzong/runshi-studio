package com.bhu.runshistudioweb.exception;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器：把异常统一转换成标准错误响应 JSON
 *
 * author: shaoshing
 *
 * <p>为什么需要它：不加这层，Controller 里一旦抛异常，Spring 默认返回的是
 * Whitelabel 错误页或原始堆栈，前端拿不到统一的 {@code BaseResponse} 结构，
 * 拦截器也无法统一处理。
 *
 * <p>两条必须遵守的规则：
 * <ol>
 *     <li><b>内部细节不能返回给前端</b>：堆栈、SQL、类名只进日志，响应里只给「系统错误」这类
 *     无害文案，否则等于免费给攻击者提供情报；</li>
 *     <li><b>HTTP 状态码统一 200</b>：业务成败由响应体的 code 表达，
 *     这样前端一个拦截器就能处理所有情况（见 {@link ErrorCode} 的号段约定）。</li>
 * </ol>
 *
 * <p>匹配顺序提示：Spring 会优先选择「最具体」的处理器，所以 {@link BusinessException}、
 * 下面几个 Sa-Token 异常都会精确命中各自的处理方法，不会落到兜底的 RuntimeException 方法里。
 *
 * <p><b>为什么必须单独处理 Sa-Token 异常</b>：它们都继承自 RuntimeException，
 * 不单独接住就会被兜底方法统一吞成 B0001「系统错误」，前端将无法区分
 * 「该跳登录页」「该提示无权限」和「服务器真的挂了」。
 *
 * <p><b>为什么还要单独处理参数校验异常</b>：{@code @Valid} 校验失败抛出的
 * MethodArgumentNotValidException 也继承自 RuntimeException，不接住就会返回 B0001「系统错误」，
 * 前端会把「账号格式不对」显示成「服务器故障」，排查方向完全被带偏。
 *
 * <p>覆盖范围小结：业务异常、Sa-Token 四类鉴权异常、DTO 字段校验失败、方法级校验失败、
 * 请求体解析失败，都已映射到明确的业务错误码，前端可据此直接给出提示。
 * 其余异常（路径参数类型不匹配 MethodArgumentTypeMismatchException、
 * 缺少必填参数 MissingServletRequestParameterException、
 * 请求方法不支持 HttpRequestMethodNotSupportedException 等）会落到兜底方法返回 B0001，
 * 需要时按同样思路补一个处理器即可。
 */
@Hidden               // 不在接口文档中暴露异常处理器本身
@RestControllerAdvice // 全局异常处理，作用于所有 @RestController
@Slf4j                // 生成 log 对象，异常必须留痕，否则线上无从排查
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /**
     * 上传大小上限，直接从 multipart 配置读取，避免在提示文案里写死数字
     * （改了 spring.servlet.multipart.max-file-size 而忘记改文案，会让用户看到错误的限制值）
     */
    @Value("${spring.servlet.multipart.max-file-size:未知}")
    private final String maxFileSize;

    /**
     * 业务异常：code 与 message 原样透传给前端
     * 这是「预期内」的失败，用 error 级别记录即可（不带堆栈），不需要告警
     *
     * @param e 业务异常
     * @return 携带原始错误码与提示的标准响应
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        // log.error 的第二个参数是异常对象，日志框架会自动打印堆栈
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 未登录：未携带 token、token 无效/已过期、被顶下线或被踢下线等
     * 由 Sa-Token 在鉴权时抛出（{@code @SaCheckLogin}、{@code StpUtil.checkLogin()} 等）
     *
     * <p>刻意使用 warn 且不打堆栈：未登录属于「预期内」的正常流程，
     * 若按 error + 堆栈记录，正常的过期登录会把日志淹掉，反而查不到真问题。
     *
     * @param e Sa-Token 未登录异常
     * @return code 固定 A0201；message 用 Sa-Token 自带的提示（如「token已过期」），
     *         文案面向用户、不含内部细节，前端据此提示并跳转登录页
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginExceptionHandler(NotLoginException e) {
        log.warn("NotLoginException | type={} | loginType={} | {}", e.getType(), e.getLoginType(), e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, e.getMessage());
    }

    /**
     * 角色不足：{@code @SaCheckRole} 校验未通过（角色数据源见
     * {@link com.bhu.runshistudioweb.service.impl.StpInterfaceImpl}）
     *
     * <p>注意：响应里只给固定的「无权限」，**不告诉调用方需要什么角色**——
     * 角色名属于权限设计信息，只写日志即可，避免为探测提供线索。
     *
     * @param e Sa-Token 角色校验异常
     * @return code 固定 A0301
     */
    @ExceptionHandler(NotRoleException.class)
    public BaseResponse<?> notRoleExceptionHandler(NotRoleException e) {
        log.warn("NotRoleException | role={} | loginType={}", e.getRole(), e.getLoginType());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR);
    }

    /**
     * 权限点不足：{@code @SaCheckPermission} 校验未通过（当前项目未使用权限点，预留）
     *
     * @param e Sa-Token 权限校验异常
     * @return code 固定 A0301
     */
    @ExceptionHandler(NotPermissionException.class)
    public BaseResponse<?> notPermissionExceptionHandler(NotPermissionException e) {
        log.warn("NotPermissionException | permission={} | loginType={}", e.getPermission(), e.getLoginType());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR);
    }

    /**
     * 账号被封禁：命中 Sa-Token 的账号封禁策略（{@code StpUtil.disable(...)} 等服务维度封禁）
     *
     * <p>与「无权限」区分开：无权限是角色不够（A0301），封禁是账号本身被限制（A0302），
     * 前端对后者的处理是「提示联系管理员」而不是「跳登录页」。
     *
     * @param e Sa-Token 封禁异常
     * @return code 固定 A0302
     */
    @ExceptionHandler(DisableServiceException.class)
    public BaseResponse<?> disableServiceExceptionHandler(DisableServiceException e) {
        log.warn("DisableServiceException | loginId={} | service={} | level={} | disableTime={}",
                e.getLoginId(), e.getService(), e.getLevel(), e.getDisableTime());
        return ResultUtils.error(ErrorCode.FORBIDDEN_ERROR, "账号已被封禁，请联系管理员");
    }

    /**
     * 参数校验失败：{@code @RequestBody @Valid} 触发的字段级校验（DTO 上的 @NotBlank / @Size / @Pattern）
     *
     * <p>只取**第一条**字段错误返回：前端一次提示一个明确原因就够了，
     * 全部返回反而让用户不知道先改哪个；完整信息在日志里。
     *
     * @param e 参数校验异常（携带 BindingResult）
     * @return code 固定 A0401，message 为字段上声明的提示文案
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e) {
        // getDefaultMessage() 取的是注解里 message 的值，例如「账号长度需在 4 ~ 16 位之间」
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
        log.warn("MethodArgumentNotValidException | {}", message);
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    /**
     * 参数校验失败：方法级校验（{@code @Validated} + {@code @RequestParam} 上的约束）
     *
     * <p>与上一个处理器的区别：{@code @RequestBody} 的校验失败走 MethodArgumentNotValidException，
     * 而请求参数、路径变量的校验失败走这个异常，两者都要接，否则总有一类会变成 B0001。
     *
     * @param e 约束违反异常
     * @return code 固定 A0401，message 为第一条违反约束的提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<?> constraintViolationExceptionHandler(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
        log.warn("ConstraintViolationException | {}", message);
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    /**
     * 请求体不可读：请求体不是合法 JSON、字段类型不匹配、缺少请求体等
     *
     * <p>典型触发场景：前端漏传 Content-Type、JSON 里把数字写成裸字符串、
     * 请求体被截断。若不接住，会落到兜底的 RuntimeException 变成 B0001「系统错误」，
     * 前端会把「参数格式不对」显示成「服务器故障」，排查方向被完全带偏。
     *
     * <p>注意：响应里**不回显 Jackson 的原始解析信息**（它可能带上类的全限定名与字段路径），
     * 只给一句无害提示；详细原因记在 warn 日志里。
     *
     * @param e 请求体解析失败异常
     * @return code 固定 A0401
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException | {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误，请检查 JSON 是否合法");
    }

    /**
     * 上传文件超过大小限制：由 Spring 在**解析请求体时**抛出，此时请求还没进入 Controller
     *
     * <p>为什么必须单独处理：否则会落到兜底的 RuntimeException 变成 B0001「系统错误」——
     * 用户传了一张过大的照片，看到"系统错误"会以为是服务器故障（去重试、去反馈），
     * 而真实原因只是"文件太大"，换张小图即可。**一次用户自己能解决的输入错误，
     * 被伪装成服务端故障**；对监控也是污染：告警会把"用户传大文件"统计成服务端异常。
     *
     * <p>提示文案里的限制值从 multipart 配置读取，不写死数字。
     *
     * @param e 上传超限异常
     * @return code 固定 A0401，提示带上当前配置的大小上限
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException | limit={} | {}", maxFileSize, e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大，单个文件不能超过 " + maxFileSize);
    }

    /**
     * 兜底异常：所有未预期异常（空指针、数据库错误等）都降级为系统错误
     * 这里必须打完整堆栈（排查依据），但返回给前端的 message 只能是「系统错误」
     *
     * @param e 未预期的运行时异常
     * @return 固定的系统错误响应，不暴露任何异常细节
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}

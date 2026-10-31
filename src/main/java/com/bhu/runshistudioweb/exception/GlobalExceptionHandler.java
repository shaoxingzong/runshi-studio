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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器：把异常统一转换成标准错误响应 JSON
 *
 * 对外只回错误码与固定文案，不回堆栈、异常类型或任何内部细节。
 *
 * @author shaoshing
 */
@Hidden               // 不在接口文档中暴露异常处理器本身
@RestControllerAdvice // 全局异常处理，作用于所有 @RestController
@Slf4j                // 生成 log 对象，异常必须留痕，否则线上无从排查
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** 上传大小上限，直接从 multipart 配置读取，避免在提示文案里写死数字 （改了 spring.servlet.multipart.max-file-size 而忘记改文案，会让用户看到错误的限制值） */
    @Value("${spring.servlet.multipart.max-file-size:未知}")
    private final String maxFileSize;

    /**
     * 处理业务异常：把错误码与提示原样透传给前端
     *
     * @param e 业务异常，携带业务错误码与面向用户的提示；不会为 null
     * @return 携带原始错误码与提示的标准响应，HTTP 状态码仍是 200
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        // log.error 的第二个参数是异常对象，日志框架会自动打印堆栈
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理「未登录」：没带凭证、凭证过期/被篡改、被顶下线或被踢下线
     *
     * @param e Sa-Token 未登录异常，携带异常类型与登录类型
     * @return code 固定 A0201；message 取 Sa-Token 自带提示，前端据此提示用户并跳转登录页
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginExceptionHandler(NotLoginException e) {
        log.warn("NotLoginException | type={} | loginType={} | {}", e.getType(), e.getLoginType(), e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, e.getMessage());
    }

    /**
     * 处理「角色不足」：{@code @SaCheckRole} 校验未通过，已登录但角色不够
     *
     * @param e Sa-Token 角色校验异常，携带缺失的角色与登录类型
     * @return code 固定 A0301，message 为枚举默认的「无权限」
     */
    @ExceptionHandler(NotRoleException.class)
    public BaseResponse<?> notRoleExceptionHandler(NotRoleException e) {
        log.warn("NotRoleException | role={} | loginType={}", e.getRole(), e.getLoginType());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR);
    }

    /**
     * 处理「权限点不足」：{@code @SaCheckPermission} 校验未通过
     *
     * @param e Sa-Token 权限点校验异常，携带缺失的权限点与登录类型
     * @return code 固定 A0301，message 为枚举默认的「无权限」
     */
    @ExceptionHandler(NotPermissionException.class)
    public BaseResponse<?> notPermissionExceptionHandler(NotPermissionException e) {
        log.warn("NotPermissionException | permission={} | loginType={}", e.getPermission(), e.getLoginType());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR);
    }

    /**
     * 处理「账号被封禁」：命中 Sa-Token 的账号封禁策略（服务维度封禁）
     *
     * @param e Sa-Token 封禁异常，携带登录 ID、服务名、封禁级别与剩余时长
     * @return code 固定 A0302，message 为「账号已被封禁，请联系管理员」，前端据此提示联系管理员而不是跳登录页
     */
    @ExceptionHandler(DisableServiceException.class)
    public BaseResponse<?> disableServiceExceptionHandler(DisableServiceException e) {
        log.warn("DisableServiceException | loginId={} | service={} | level={} | disableTime={}",
                e.getLoginId(), e.getService(), e.getLevel(), e.getDisableTime());
        return ResultUtils.error(ErrorCode.FORBIDDEN_ERROR, "账号已被封禁，请联系管理员");
    }

    /**
     * 处理「请求体字段校验失败」：{@code @RequestBody @Valid} 触发的 DTO 字段约束
     *
     * @param e 参数校验异常，内部携带 BindingResult（含全部字段错误）
     * @return code 固定 A0401，message 为字段注解上声明的提示文案；前端可以直接把它展示在表单上方
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
     * 处理「查询参数对象绑定校验失败」：{@code @Valid} 标在 GET 查询 DTO 上时抛出
     *
     * @param e 绑定校验异常，内部携带全部字段错误
     * @return code 固定 A0401，message 为第一条字段约束的提示文案
     */
    @ExceptionHandler(BindException.class)
    public BaseResponse<?> bindExceptionHandler(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
        log.warn("BindException | {}", message);
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    /**
     * 处理「方法级参数校验失败」：{@code @Validated} 加在类上、约束加在 {@code @RequestParam} / 路径变量上的那种校验
     *
     * @param e 约束违反异常，内部携带全部违反项（每项含注解上的 message）
     * @return code 固定 A0401，message 为第一条违反约束的提示文案
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
     * 处理「请求体读不出来」：JSON 语法错误、字段类型对不上、请求体被截断等
     *
     * @param e 请求体解析失败异常，原始信息里含字段路径与期望类型
     * @return code 固定 A0401，message 为「请求体格式错误，请检查 JSON 是否合法」
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException | {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误，请检查 JSON 是否合法");
    }

    /**
     * 处理「上传文件超过大小限制」：由 Spring 在解析 multipart 请求时抛出，此时请求还没进入 Controller
     *
     * @param e 上传超限异常
     * @return code 固定 A0401，message 为「上传文件过大，单个文件不能超过 X」 （X 取自 {@code spring.servlet.multipart.max-file-size} 的当前配置）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException | limit={} | {}", maxFileSize, e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大，单个文件不能超过 " + maxFileSize);
    }

    /**
     * 兜底处理：所有未被上面任何处理器接住的运行时异常，统一降级为「系统错误」
     *
     * @param e 未预期的运行时异常，可能来自任何一层
     * @return 固定的 B0001 系统错误响应，message 恒为「系统错误」，不含异常类型、堆栈或任何内部细节
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}

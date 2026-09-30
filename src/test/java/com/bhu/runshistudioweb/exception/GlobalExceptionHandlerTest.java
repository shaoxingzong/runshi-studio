package com.bhu.runshistudioweb.exception;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.bhu.runshistudioweb.common.BaseResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器的验收测试
 *
 * author: shaoshing
 *
 * <p>这里直接 {@code new GlobalExceptionHandler()} 调用方法，不启动 Spring 容器：
 * 被测逻辑是纯映射（异常 → 响应体），没有依赖需要注入，用普通单元测试跑得更快、更稳。
 * 容器层面的装配（@RestControllerAdvice 是否生效）由集成测试覆盖。
 *
 * <p>Sa-Token 那 4 个用例的价值在于「防止被兜底吞掉」：
 * 它们都继承 RuntimeException，一旦处理器被误删，接口会返回 50000，
 * 前端就无法判断该跳登录页还是该报服务器故障。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常：透传原始错误码与提示信息")
    void businessExceptionKeepsCodeAndMessage() {
        // 业务异常是「预期内的失败」，不能降级成 50000，否则前端无法区分参数错和系统错
        BaseResponse<?> response = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        assertEquals(40100, response.getCode());
        assertEquals("未登录", response.getMessage());
    }

    @Test
    @DisplayName("业务异常：自定义错误码与提示同样被透传")
    void businessExceptionWithCustomCode() {
        BaseResponse<?> response = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能超过 64 字符"));
        assertEquals(40000, response.getCode());
        assertEquals("账号长度不能超过 64 字符", response.getMessage());
    }

    @Test
    @DisplayName("运行时异常：统一降级为系统错误码 50000")
    void runtimeExceptionMappedToSystemError() {
        // 未预期异常一律收敛到同一个 code，避免内部实现细节（异常类型）泄露给前端
        BaseResponse<?> response = handler.runtimeExceptionHandler(
                new RuntimeException("数据库连接失败"));
        assertEquals(50000, response.getCode());
    }

    @Test
    @DisplayName("安全底线：运行时异常的内部信息绝不能泄露给前端")
    void runtimeExceptionMustNotLeakInternalDetail() {
        // 这条是安全测试，不是格式测试：SQL、表名、异常类名都是攻击者的情报
        String internalDetail = "Table 'studio_db.sys_user' doesn't exist";
        BaseResponse<?> response = handler.runtimeExceptionHandler(
                new RuntimeException(internalDetail));
        assertNotNull(response.getMessage());
        assertFalse(response.getMessage().contains(internalDetail),
                "响应 message 泄露了内部异常信息：" + response.getMessage());
        assertFalse(response.getMessage().contains("RuntimeException"),
                "响应 message 泄露了异常类名：" + response.getMessage());
    }

    @Test
    @DisplayName("空指针等未预期异常也应由兜底处理器接管")
    void nullPointerExceptionHandled() {
        // NPE 继承自 RuntimeException，必须被兜底方法接住，否则会返回 Spring 默认的白页错误
        BaseResponse<?> response = handler.runtimeExceptionHandler(
                new NullPointerException("cannot invoke method"));
        assertEquals(50000, response.getCode());
        assertEquals("系统错误", response.getMessage());
    }

    @Test
    @DisplayName("Sa-Token 未登录：映射为 40100，绝不能被兜底成 50000")
    void notLoginExceptionMappedTo40100() {
        // 构造参数顺序：message、loginType、type（type 为 NotLoginException 的负数常量）
        BaseResponse<?> response = handler.notLoginExceptionHandler(
                new NotLoginException("token已过期", "login", NotLoginException.TOKEN_TIMEOUT));

        assertEquals(40100, response.getCode(), "未登录被兜底成了系统错误，前端不会跳登录页");
        // 用 Sa-Token 自带的提示，让前端能直接展示「token已过期」这类具体原因
        assertEquals("token已过期", response.getMessage());
    }

    @Test
    @DisplayName("Sa-Token 角色不足：映射为 40101，且不泄露需要什么角色")
    void notRoleExceptionMappedTo40101() {
        BaseResponse<?> response = handler.notRoleExceptionHandler(
                new NotRoleException("admin", "login"));

        assertEquals(40101, response.getCode());
        assertFalse(response.getMessage().contains("admin"),
                "响应里泄露了所需角色，等于给探测者提供线索：" + response.getMessage());
    }

    @Test
    @DisplayName("Sa-Token 权限点不足：映射为 40101，且不泄露需要什么权限")
    void notPermissionExceptionMappedTo40101() {
        BaseResponse<?> response = handler.notPermissionExceptionHandler(
                new NotPermissionException("user:update", "login"));

        assertEquals(40101, response.getCode());
        assertFalse(response.getMessage().contains("user:update"),
                "响应里泄露了所需权限点：" + response.getMessage());
    }

    @Test
    @DisplayName("Sa-Token 账号封禁：映射为 40300，与「无权限」区分开")
    void disableServiceExceptionMappedTo40300() {
        BaseResponse<?> response = handler.disableServiceExceptionHandler(
                new DisableServiceException("login", 10001L, "comment", 2, 1, 7200));

        assertEquals(40300, response.getCode());
    }

    @Test
    @DisplayName("DTO 校验失败：映射为 40000，并把注解上写的提示文案带给前端")
    void methodArgumentNotValidMappedTo40000() throws Exception {
        // 手工拼一个「校验失败」的异常：真实场景由 @RequestBody @Valid 触发
        MethodParameter parameter = new MethodParameter(
                Object.class.getDeclaredMethod("toString"), -1);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "userRegisterRequest");
        bindingResult.addError(new FieldError("userRegisterRequest", "userAccount",
                "账号长度需在 4 ~ 16 位之间"));
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(parameter, bindingResult);

        BaseResponse<?> response = handler.methodArgumentNotValidExceptionHandler(exception);

        // 校验失败绝不能变成 50000：那样「账号格式不对」会被前端显示成「服务器故障」
        assertEquals(40000, response.getCode(), "参数校验失败被兜底成了系统错误");
        assertEquals("账号长度需在 4 ~ 16 位之间", response.getMessage());
    }

    @Test
    @DisplayName("请求体解析失败：映射为 40000，且不回显 Jackson 的原始报错")
    void httpMessageNotReadableMappedTo40000() {
        // 真实的 message 里带类的全限定名与解析细节，绝不能透给前端
        String internalDetail = "JSON parse error: Unexpected character ('}' (code 125)): "
                + "com.bhu.runshistudioweb.model.dto.user.UserLoginRequest";
        BaseResponse<?> response = handler.httpMessageNotReadableExceptionHandler(
                new HttpMessageNotReadableException(internalDetail, (HttpInputMessage) null));

        assertEquals(40000, response.getCode());
        assertFalse(response.getMessage().contains("UserLoginRequest"),
                "响应里回显了内部类名：" + response.getMessage());
        assertFalse(response.getMessage().contains("Unexpected character"),
                "响应里回显了 Jackson 的原始解析信息：" + response.getMessage());
    }

    @Test
    @DisplayName("方法级校验失败：ConstraintViolationException 同样映射为 40000")
    void constraintViolationMappedTo40000() {
        // ConstraintViolation 由 Hibernate Validator 运行时生成，单测里用 Mockito 造一个即可
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("页码不能小于 1");
        ConstraintViolationException exception =
                new ConstraintViolationException(Set.of(violation));

        BaseResponse<?> response = handler.constraintViolationExceptionHandler(exception);

        assertEquals(40000, response.getCode());
        assertEquals("页码不能小于 1", response.getMessage());
    }
}

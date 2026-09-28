package com.bhu.runshistudioweb.exception;

import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 * <p>匹配顺序提示：Spring 会优先选择「最具体」的处理器，所以 {@link BusinessException}
 * 一定会被下面的业务异常方法接住，不会落到兜底的 RuntimeException 方法里，两个方法的顺序不影响结果。
 *
 * <p><b>待补充</b>：参数校验异常（MethodArgumentNotValidException、ConstraintViolationException）
 * 目前会被兜底的 RuntimeException 接成 50000，需要补专门的处理器返回 40000。
 */
@Hidden               // 不在接口文档中暴露异常处理器本身
@RestControllerAdvice // 全局异常处理，作用于所有 @RestController
@Slf4j                // 生成 log 对象，异常必须留痕，否则线上无从排查
public class GlobalExceptionHandler {

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

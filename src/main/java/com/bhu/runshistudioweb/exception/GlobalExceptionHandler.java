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
 * 没有这层时，Controller 抛异常会返回 Whitelabel 错误页或原始堆栈，
 * 前端拿不到统一的 BaseResponse 结构，拦截器也无从统一处理。
 *
 * @author shaoshing
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
     * 处理业务异常：把错误码与提示原样透传给前端
     *
     * 这类异常是「预期内的失败」，code 与 message 本来就是给用户看的。
     * 若像兜底方法那样统一成「系统错误」，「账号已存在」会变成「系统错误」，
     * 用户去反馈服务器坏了，而运维翻日志只找到一条预期内的业务拒绝。
     *
     * 注意不吞异常、不换码：任何对 code 的「顺手纠正」都会让前端的分支判断失效，
     * 例如把 A0302（封禁）改成 A0301（无权限），前端就会错误地引导用户重新登录。
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
     * 单独接住才能返回 A0201，让前端明确做两件事：清掉本地登录态、跳登录页。
     * 落到兜底会变成 B0001，前端只弹「系统错误」，而监控还会把「token 过期」
     * 统计成服务端异常，把真正的故障淹没。
     *
     * 日志用 warn 且不打堆栈：未登录是预期内的正常流程，按 error + 堆栈记录会把日志刷满。
     *
     * @param e Sa-Token 未登录异常，携带异常类型与登录类型
     * @return code 固定 A0201；message 取 Sa-Token 自带提示，
     *         前端据此提示用户并跳转登录页
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginExceptionHandler(NotLoginException e) {
        log.warn("NotLoginException | type={} | loginType={} | {}", e.getType(), e.getLoginType(), e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, e.getMessage());
    }

    /**
     * 处理「角色不足」：{@code @SaCheckRole} 校验未通过，已登录但角色不够
     *
     * 与未登录的处置完全不同：那边该跳登录页，这边该提示无权限、留在当前页。
     * 两者被兜成同一个 B0001，前端只能给「系统错误」，用户会以为系统坏了反复重试。
     *
     * 响应只给「无权限」，不告诉调用方「你需要 admin」：把「这个接口需要什么角色」
     * 写进响应等于免费告诉探测者「拿下 admin 就能进」。角色名只进日志。
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
     * 本项目目前用角色做粗粒度控制，还没有权限点，但 Sa-Token 这套机制是现成的。
     * 先写好处理器是因为漏掉它不会在编译期暴露：运行期落到兜底变成 B0001，
     * 真实原因是「你没有这个权限点」，往往要等上线后由用户反馈才发现。
     *
     * 与角色不足映射到同一个 A0301：对前端来说两者的处置一样（提示无权限）。
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
     * 落到兜底会让「你的账号被封了」变成「服务器故障」，用户反复重试、去反馈系统坏了。
     * 必须与「无权限」分开：无权限换个账号即可，封禁换设备、重新登录都没用，只能联系管理员。
     *
     * 坑：这里接的是 Sa-Token 封禁（鉴权阶段拦截会话），与业务表里的「用户状态 = 封禁」
     * 是两套机制。只做业务侧封禁却忘了踢会话，旧 token 还能继续用，本方法根本不会被触发。
     *
     * @param e Sa-Token 封禁异常，携带登录 ID、服务名、封禁级别与剩余时长
     * @return code 固定 A0302，message 为「账号已被封禁，请联系管理员」，
     *         前端据此提示联系管理员而不是跳登录页
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
     * 不接住它，DTO 注解上写的 message 一个字符都到不了前端，用户只看到「系统错误」，
     * 只能瞎试。只返回第一条字段错误：一次表单提交可能五个字段都不合法，
     * 全部返回会让用户不知道先改哪个，完整信息留在日志里。
     *
     * 坑：别把 message 换成 {@code e.getMessage()}，Spring 的原始信息带类的全限定名
     * 与字段路径，既不友好，也泄露了内部类名。
     *
     * @param e 参数校验异常，内部携带 BindingResult（含全部字段错误）
     * @return code 固定 A0401，message 为字段注解上声明的提示文案；
     *         前端可以直接把它展示在表单上方
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
     * 不接住就会落到兜底变成 B0001：用户只是输入的关键词太长，却看到「服务器故障」，
     * 既无法行动，也会把输入问题算成服务端异常。与请求体校验保持同一口径。
     *
     * Spring 6+ 中 MethodArgumentNotValidException 继承自 BindException，
     * 上面更具体的处理器会优先命中，本方法只兜住普通模型属性绑定这一支。
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
     * 处理「方法级参数校验失败」：{@code @Validated} 加在类上、约束加在
     * {@code @RequestParam} / 路径变量上的那种校验
     *
     * 与上面那个异常长得像，但来源完全不同：一个来自请求体里 DTO 的字段校验，
     * 一个来自方法参数（查询串、路径变量）上的约束。Spring 抛的是两种异常，
     * 只写一个的话另一类会掉进兜底变成 B0001。
     *
     * 坑：这类校验只有类上标了 {@code @Validated} 才生效，光在参数上写 {@code @Min}
     * 会被静默忽略，参数照样进方法，后面的业务代码就收到非法值。
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
     * 它发生在反序列化阶段，此时还没进 Controller，{@code @Valid} 根本没轮到执行。
     * 不接住就会落到兜底变成 B0001：前端多写一个逗号，会被显示成「服务器故障」，
     * 前端以为后端挂了、后端以为是业务异常，排查方向被完全带偏。
     *
     * 刻意不回显 Jackson 的原始信息：它带 DTO 的全限定类名与字段路径，
     * 既不面向用户，也等于把内部结构告诉了调用方。细节留在日志里。
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
     * 处理「上传文件超过大小限制」：由 Spring 在解析 multipart 请求时抛出，
     * 此时请求还没进入 Controller
     *
     * 不接住会把一次用户自己能解决的输入错误伪装成服务端故障：用户以为服务器挂了，
     * 于是重试、反馈；监控也会把「用户传大文件」统计成服务端异常。
     *
     * 这个异常在请求体解析阶段抛出，Controller 里的任何兜底判空都不会执行，
     * 只能由全局处理器接住。上限从配置读取，不写死数字，否则改了配置忘了改文案，
     * 用户会按错误的限制值反复压缩文件。
     *
     * @param e 上传超限异常
     * @return code 固定 A0401，message 为「上传文件过大，单个文件不能超过 X」
     *         （X 取自 {@code spring.servlet.multipart.max-file-size} 的当前配置）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException | limit={} | {}", maxFileSize, e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大，单个文件不能超过 " + maxFileSize);
    }

    /**
     * 兜底处理：所有未被上面任何处理器接住的运行时异常，统一降级为「系统错误」
     *
     * 没有这一层，Spring 会返回 Whitelabel 错误页或一段 HTML 堆栈，
     * 前端拿到的不是统一响应结构，拦截器直接解析失败，用户看到白屏而不是提示。
     *
     * 两条不能让步：必须记 error 日志并带完整堆栈（这是排查线上问题的唯一依据）；
     * 绝不把异常细节返回给前端——堆栈里通常含 SQL 片段、表名、文件路径、内部类名，
     * 返回出去等于免费给攻击者一份系统结构图。
     *
     * 坑：每次发现某个异常返回了 B0001，正确做法是为它补一个专用处理器，
     * 而不是在这里加 if 判断类型。判断标准很简单：如果这个异常能让用户做点什么
     * （改参数、重新登录、换个小文件），它就不该由本方法处理。
     *
     * @param e 未预期的运行时异常，可能来自任何一层
     * @return 固定的 B0001 系统错误响应，message 恒为「系统错误」，
     *         不含异常类型、堆栈或任何内部细节
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}

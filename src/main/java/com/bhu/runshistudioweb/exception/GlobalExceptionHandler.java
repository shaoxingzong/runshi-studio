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
 * 为什么需要它：不加这层，Controller 里一旦抛异常，Spring 默认返回的是
 * Whitelabel 错误页或原始堆栈，前端拿不到统一的 BaseResponse 结构，
 * 拦截器也无法统一处理。
 *
 * 两条必须遵守的规则：
 * - 内部细节不能返回给前端：堆栈、SQL、类名只进日志，响应里只给「系统错误」这类
 * 无害文案，否则等于免费给攻击者提供情报；
 * - HTTP 状态码统一 200：业务成败由响应体的 code 表达，
 * 这样前端一个拦截器就能处理所有情况（见 ErrorCode 的号段约定）。
 *
 * 匹配顺序提示：Spring 会优先选择「最具体」的处理器，所以 BusinessException、
 * 下面几个 Sa-Token 异常都会精确命中各自的处理方法，不会落到兜底的 RuntimeException 方法里。
 *
 * 为什么必须单独处理 Sa-Token 异常：它们都继承自 RuntimeException，
 * 不单独接住就会被兜底方法统一吞成 B0001「系统错误」，前端将无法区分
 * 「该跳登录页」「该提示无权限」和「服务器真的挂了」。
 *
 * 为什么还要单独处理参数校验异常：@Valid 校验失败抛出的
 * MethodArgumentNotValidException 也继承自 RuntimeException，不接住就会返回 B0001「系统错误」，
 * 前端会把「账号格式不对」显示成「服务器故障」，排查方向完全被带偏。
 *
 * 覆盖范围小结：业务异常、Sa-Token 四类鉴权异常、DTO 字段校验失败、方法级校验失败、
 * 请求体解析失败，都已映射到明确的业务错误码，前端可据此直接给出提示。
 * 其余异常（路径参数类型不匹配 MethodArgumentTypeMismatchException、
 * 缺少必填参数 MissingServletRequestParameterException、
 * 请求方法不支持 HttpRequestMethodNotSupportedException 等）会落到兜底方法返回 B0001，
 * 需要时按同样思路补一个处理器即可。
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
     * <p>为什么这样设计：BusinessException 是「预期内的失败」——参数不合法、资源不存在、
     * 没有权限、业务规则不允许，这些都不是程序 bug，而是业务对这次请求给出了否定答复。
     * 因此它的 code 与 message 本来就是给用户看的，必须原样透传：
     * 若像兜底方法那样统一成「系统错误」，用户会看到「账号已存在」变成「系统错误」，
     * 于是去反馈「服务器坏了」，而运维去翻日志却只找到一条预期内的业务拒绝。
     * 另一个取舍是日志用 error 级别但可以不带堆栈：它需要留痕（谁在什么情况下被拒），
     * 但不需要告警——业务拒绝的数量本来就跟用户操作量成正比，告警会被淹没。
     * 注意这里<b>不吞异常、不换码</b>：任何对 code 的「顺手纠正」都会让前端的分支判断失效，
     * 例如把 A0302（封禁）改成 A0301（无权限），前端就会错误地引导用户去重新登录。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 先记日志，再返回响应。顺序不能反：万一构造响应时又出问题（例如 message 为 null），
     *     至少日志里已经留下了这次业务异常的原始信息；</li>
     *     <li>② 用异常自带的 code 与 message 构造标准响应。业务异常一定带这两样
     *     （构造器强制要求错误码），因此这里不需要判空，也不该用错误码枚举的默认文案
     *     去覆盖它——那样会丢掉「哪一条数据不存在」这类具体信息。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要在这里把 BusinessException 的 message 改成通用文案。
     * 业务异常的提示是<b>面向用户的可执行指引</b>（「该账号还没有成员档案，请先到成员管理建档」），
     * 一旦统一成「操作失败」，用户就失去了下一步该做什么的线索，
     * 而这种损失不会报错，只会表现为「客服反复被问同一个问题」。
     * 反过来也要守住另一条边界：写业务异常提示时不能把 SQL、类名、堆栈拼进去，
     * 因为这个 message 会原样出现在前端。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：它继承自 RuntimeException，
     * 不单独接住就会落到下面的兜底方法，变成 B0001「系统错误」。
     * 后果很具体：前端拦截器靠 code 决定要不要跳登录页——收到 B0001 时它只会弹一个
     * 「系统错误」的提示，而用户其实只是登录过期了，重新登录即可；
     * 更糟的是监控会把「用户 token 过期」统计成服务端异常，把真正的故障淹没。
     * 单独接住之后，A0201 让前端明确做两件事：清掉本地登录态、跳登录页。
     * 另一个刻意的取舍是日志用 warn 且<b>不打堆栈</b>：未登录是预期内的正常流程
     * （token 有有效期，到期就会走到这里），按 error + 堆栈记录的话，
     * 正常的过期登录会把日志刷满，真问题反而找不到。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记一行结构化日志：类型、登录类型、原始提示。
     *     带 type 是因为「没带 token」与「token 已过期」的排查方向完全不同，
     *     只记一句 message 会分不清；</li>
     *     <li>② 返回固定错误码 A0201，message 用 Sa-Token 自带的提示
     *     （如「token 已过期」）。这段文案面向用户、不含内部细节，
     *     比统一写「未登录」更能解释「为什么会掉线」。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把这里的 code 改成 HTTP 语义的 401。本项目的接口
     * HTTP 状态一律 200，业务成败只看响应体里的 code；混用两套状态会让前端
     * 拦截器出现「有些 401 走 axios 错误分支、有些走业务分支」的分裂。
     * 另一个坑是别在这里顺手清理服务端会话——注销动作应当由显式的登出接口完成，
     * 在异常处理器里做副作用，会让「查询接口返回 A0201」这种只读请求
     * 也产生一次写操作，很难解释。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：它同样继承自 RuntimeException，不单独接住
     * 就会落到兜底方法变成 B0001「系统错误」。而这与未登录的处置完全不同：
     * 未登录该「跳登录页」，角色不足该「提示无权限、留在当前页」。
     * 两者若被兜成同一个 B0001，前端只能给出「系统错误」——
     * 用户会以为系统坏了，反复重试，而他真正需要的只是换一个有权限的账号。
     * 单独接住之后，A0301 让前端能明确区分「你登录了，但这个功能不属于你」。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记下缺失的角色与登录类型。角色名只进日志、不进响应——
     *     它是排查「为什么这个人被挡了」的唯一线索，缺了它就只能猜；</li>
     *     <li>② 返回固定错误码 A0301，且只给「无权限」这一句固定文案，
     *     不告诉调用方「你需要 admin」。这是刻意的最小信息披露：
     *     把「这个接口需要什么角色」写进响应，等于免费告诉探测者
     *     「拿下 admin 就能进」，而正常用户本来也不需要知道具体角色名。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要把角色名拼进 message 图方便。这类提示看着更友好，
     * 实际是在对外暴露权限设计；需要排查时日志里已经有了。
     * 另一个坑是别把本方法合并到 NotLoginException 的处理里——
     * 它们的错误码不同（A0201 与 A0301），前端正是靠这个差异决定
     * 「跳登录页」还是「留在原地提示」，合并会让其中一种场景的行为错掉。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：本项目目前用角色（user / member / admin）做粗粒度控制，
     * 还没有引入权限点（permission，比角色更细的一层，例如「能删帖子」但「不能删用户」）。
     * 但 Sa-Token 的这套机制是现成的，将来一旦加上 {@code @SaCheckPermission}，
     * 它抛出的就是本异常。现在先把处理器写好，是因为漏掉它的后果不在编译期暴露：
     * 运行期它会落到兜底方法变成 B0001，前端拿到「系统错误」，
     * 而真实原因是「你没有这个权限点」——这类问题往往要等到上线后由用户反馈才发现。
     * 与角色不足一样，它映射到同一个错误码 A0301：对前端来说，
     * 「角色不够」与「权限点不够」的处置是一样的（提示无权限），不需要分成两个码。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记下缺失的权限点与登录类型。权限点名称写进日志是必要的——
     *     将来真出问题时，你需要知道是哪一个 permission 字符串对不上；</li>
     *     <li>② 返回固定错误码 A0301 与默认文案，与角色不足保持完全一致的对外表现。</li>
     * </ol>
     *
     * <p>容易踩的坑：别因为「当前没有权限点」就把这个方法删掉。删掉之后不会有任何报错，
     * 直到某天有人加了第一个 {@code @SaCheckPermission}，那个接口的失败
     * 就会悄悄变成 B0001，而排查的人会先去查服务端日志找「系统异常」，
     * 方向从一开始就错了。另外也别把它合并进 NotRoleException 的处理器：
     * Spring 按异常类型精确匹配，合并后必然有一个类型接不住。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：它继承自 RuntimeException，不单独接住就会
     * 落到兜底方法变成 B0001「系统错误」，于是「你的账号被封了」变成「服务器故障」——
     * 用户会反复重试、甚至去反馈「系统坏了」，而管理员还得从日志里反推
     * 「其实是这个人被自己封了」。单独接住之后，A0302 让前端能给出
     * 「账号已被封禁，请联系管理员」这类可行动的信息。
     * 为什么还要与「无权限」分开：两者的处置完全相反——无权限是角色不够，
     * 换个有权限的账号就能继续；封禁是账号本身被限制，换设备、重新登录都没用，
     * 只能联系管理员。若都映射成 A0301，前端会把被封禁的用户引导去重新登录，
     * 他登录完发现还是不行，只会更困惑。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记下登录 ID、服务名、封禁级别与封禁时长。
     *     这四个值合起来才够还原「谁、在哪个服务、被禁了多久」，
     *     少任何一个都得回头猜；</li>
     *     <li>② 返回固定错误码 A0302，并给一句面向用户的固定文案。
     *     这里不用异常自带的 message——它可能带内部的服务名或时间戳格式，
     *     而用户只需要知道「找管理员」。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法处理的是 Sa-Token 的封禁（服务维度），
     * 与业务表里的「用户状态 = 封禁」是两套机制。业务封禁在登录时就会被拦下，
     * 而 Sa-Token 封禁是在<b>鉴权阶段</b>拦截已登录的会话。
     * 如果只做了业务侧封禁却忘了踢会话，用户手里的旧 token 还能继续用，
     * 直到过期为止——那时候本方法根本不会被触发，因为请求压根没被判定为封禁。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：不接住它，前端只能拿到一句「系统错误」。
     * 而这个异常携带的信息恰恰是用户最需要的——哪个字段、违反了哪条约束、
     * 应该怎么改。DTO 上 {@code @Size(min = 4, max = 16, message = "账号长度需在 4-16 位")}
     * 里的 message 就是为这一刻准备的；不单独处理，它连一个字符都到不了前端，
     * 用户看到「服务器故障」后只能瞎试，客服也只能靠猜。
     * 另一个取舍是<b>只返回第一条</b>字段错误：一次表单提交可能同时有五个字段不合法，
     * 全部返回会让用户不知道先改哪个，提示区域也放不下；
     * 一次一个明确原因、改完再提交，反而更快。完整信息留在日志里供排查。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从 BindingResult 里取第一条字段错误，读它注解上声明的 message。
     *     用 {@code findFirst} 而不是遍历，正是上面说的「一次只报一条」；</li>
     *     <li>② 取不到时退回错误码枚举的默认文案。这个分支理论上不会走到
     *     （校验失败至少有一条错误），但 {@code orElse} 让「没有错误却抛了异常」
     *     这种反常情况退化成一句无害提示，而不是空指针变成 B0001；</li>
     *     <li>③ 用 warn 记录提示文案后返回 A0401。级别是 warn 而不是 error：
     *     这是用户输入问题，不是系统故障，用 error 会让监控把「用户填错」算成服务异常。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只覆盖 {@code @RequestBody} 上的校验。
     * 同样的注解写在 {@code @RequestParam} 或路径变量上时，抛的是下面那个
     * ConstraintViolationException——两个处理器必须都留着，
     * 否则总有一类参数错误会掉进兜底变成 B0001。
     * 另一个坑是别把 message 换成 {@code e.getMessage()}：Spring 的原始异常信息
     * 会带上类的全限定名与字段路径（形如
     * {@code Field error in object 'userAddRequest' on field 'userAccount'}），
     * 既不友好，也泄露了内部类名。
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
     * 处理「方法级参数校验失败」：{@code @Validated} 加在类上、约束加在
     * {@code @RequestParam} / 路径变量上的那种校验
     *
     * <p>为什么这样设计：为什么这个异常要单独处理：它和上面那个 MethodArgumentNotValidException
     * 长得像，但<b>来源完全不同</b>——一个来自请求体里 DTO 的字段校验，
     * 另一个来自方法参数（查询串、路径变量）上的约束。Spring 抛的是两种异常，
     * 因此必须有两个处理器。只写一个的话，另一类参数错误会掉进兜底方法变成
     * B0001「系统错误」：用户把 {@code ?pageSize=abc} 传错，看到的却是「服务器故障」，
     * 于是反复重试同一个错误请求。两个都接住之后，所有「输入不合法」都能稳定落到 A0401。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从约束违反集合里取第一条，读它注解上声明的 message。
     *     与上一个处理器一样只报一条：多个参数同时不合法时，
     *     一次给一个明确原因比一次给五条更有效；</li>
     *     <li>② 取不到时退回错误码枚举的默认文案，兜住「异常里没有违反项」这种反常情况；</li>
     *     <li>③ 记 warn 后返回 A0401，与请求体校验失败保持同一个错误码——
     *     对前端来说，「哪个参数不对」的处理方式是一样的，不需要分成两个码。</li>
     * </ol>
     *
     * <p>容易踩的坑：这类校验只有在类上标了 {@code @Validated} 时才生效，
     * 光在参数上写 {@code @Min} 是没用的——注解会被静默忽略，参数照样进方法，
     * 而这种「校验写了但没生效」不会报错，只会让后面的业务代码收到非法值。
     * 另一个坑是别把这个处理器当成上面那个的替代品：两者接的是不同的异常类型，
     * Spring 按类型精确匹配，合并必然漏掉一类。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：它发生在 Spring 把请求体反序列化成 DTO 的阶段，
     * 此时还<b>没进 Controller</b>，{@code @Valid} 根本没轮到执行。
     * 不接住的话它会落到兜底方法变成 B0001「系统错误」——
     * 一个前端多写了一个逗号的 JSON 错误，会被显示成「服务器故障」，
     * 排查方向被完全带偏：前端以为是后端挂了，后端以为是业务异常。
     * 单独接住后，A0401 让前端知道「这次请求的格式有问题」，
     * 而且提示里直接点出「请检查 JSON 是否合法」，比一句「参数错误」可操作得多。
     * 典型触发场景：漏传 Content-Type（Spring 找不到合适的消息转换器）、
     * 把数字写成裸字符串（如 {@code "id": "abc"} 而字段是 Long）、请求体被网关截断。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记录原始解析信息。它包含出错的位置与期望类型，
     *     是排查「到底是哪个字段的类型不对」的唯一线索，因此必须进日志；</li>
     *     <li>② 返回固定错误码 A0401 与一句固定文案。
     *     这里刻意<b>不回显</b> Jackson 的原始信息：它会带上 DTO 的全限定类名
     *     与字段路径（如 {@code Cannot deserialize value of type Long from String}），
     *     既不面向用户，也等于把内部结构告诉了调用方。细节留在第 ① 步的日志里。</li>
     * </ol>
     *
     * <p>容易踩的坑：别把本方法与参数校验失败混为一谈。参数校验失败说明
     * 「JSON 是合法的，但值不满足业务约束」（例如账号长度 3 位），
     * 本方法说明「JSON 本身就不成立」。两者都返回 A0401 是对的，
     * 但排查时方向完全不同：前者去改字段值，后者去改请求体的结构。
     * 另一个坑是空请求体也会走到这里——那说明前端把 body 弄丢了，
     * 而提示文案里的「JSON 是否合法」会让人以为是格式问题，需要结合日志里的
     * 「Required request body is missing」一起看。
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
     * <p>为什么这样设计：为什么这个异常要单独处理：不接住的话，它会落到兜底方法变成
     * B0001「系统错误」——用户传了一张过大的照片，看到「系统错误」会以为是服务器故障，
     * 于是去重试、去反馈；而真实原因只是「文件太大」，换张小图即可。
     * 这就把<b>一次用户自己能解决的输入错误，伪装成了服务端故障</b>。
     * 对监控也是污染：告警会把「用户传大文件」统计成服务端异常，
     * 真正需要运维介入的故障反而被这类噪音淹没。
     * 单独接住之后，A0401 加上一句带具体上限的提示，用户当场就知道该怎么改。
     * 提示里的限制值从 multipart 配置读取，不写死数字——否则改了配置忘了改文案，
     * 用户会看到一条错误的限制值，然后按这个错误的数字反复压缩文件。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 warn 记下当前配置的上限与原始异常信息。带上限是因为
     *     「用户传了 30MB、上限是 10MB」和「上限配错了只有 1KB」是两种完全不同的问题；</li>
     *     <li>② 返回固定错误码 A0401，提示里拼上从配置读到的上限。
     *     这条提示必须是可行动的——只说「上传失败」等于让用户去猜。</li>
     * </ol>
     *
     * <p>容易踩的坑：这个异常在请求体解析阶段就抛出了，此时 Controller 里的
     * 任何兜底判空都不会执行，所以它必须由全局处理器接住，没有别的地方可以兜。
     * 另一个坑是上限有两个配置（单文件与单次请求总大小），用户上传多个文件时
     * 可能单个都没超、总量超了，此时提示里的「单个文件不能超过 X」会略有偏差——
     * 若将来要精确区分，需要为总大小单独写一条提示。
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
     * <p>为什么这样设计：为什么需要这一层：上面的处理器覆盖的都是「预料之中」的异常类型，
     * 但线上永远会有没预料到的——空指针、数组越界、数据库连接中断、
     * 某个第三方库自己抛的运行时异常。没有兜底的话，Spring 会返回
     * Whitelabel 错误页或一段 HTML 堆栈，前端拿到的不是统一响应结构，
     * 拦截器直接解析失败，用户看到的是白屏而不是一句错误提示。
     * 这一层是最后一道网，因此有两条不能让步的纪律：
     * 一是<b>必须记 error 日志并带完整堆栈</b>——它是排查线上问题的唯一依据，
     * 缺了堆栈就只剩一行「RuntimeException」，等于没记；
     * 二是<b>绝不把异常细节返回给前端</b>，只给一句「系统错误」。
     * 堆栈里通常含 SQL 片段、表名、文件路径、内部类名，
     * 返回出去等于免费给攻击者提供一份系统结构图。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 用 error 级别记录异常对象本身（而不是 {@code e.getMessage()}）。
     *     日志框架看到第二个参数是 Throwable 时会自动打印完整堆栈，
     *     这一步写错成只记 message，就会丢掉定位问题最需要的调用栈；</li>
     *     <li>② 返回固定的 B0001 与固定文案。不拼任何来自异常的信息——
     *     哪怕是「空指针」这种看起来无害的词，也会让攻击者知道
     *     「这个参数能让服务端崩」，从而找到继续探测的入口。</li>
     * </ol>
     *
     * <p>容易踩的坑：这一层很容易被当成「万能兜底」而无脑扩大——
     * 每次发现某个异常返回了 B0001，正确做法是<b>为它补一个专用处理器</b>，
     * 而不是在这里加 if 判断类型。因为一旦落到这里，用户得到的永远是
     * 「系统错误」这种无法行动的信息，而绝大多数「异常」其实是用户的输入问题。
     * 判断标准很简单：如果这个异常能让用户做点什么（改参数、重新登录、换个小文件），
     * 它就不该由本方法处理。
     * 另一个坑是别在这里把异常吞掉并返回成功——那会让失败静默消失，
     * 比返回 B0001 危险得多。
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

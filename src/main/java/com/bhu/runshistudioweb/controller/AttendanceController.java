package com.bhu.runshistudioweb.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.bhu.runshistudioweb.common.BaseResponse;
import com.bhu.runshistudioweb.common.ResultUtils;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMeVO;
import com.bhu.runshistudioweb.service.AttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考勤接口（仅工作室成员可用）
 *
 * 准入规则：三个接口都先要求登录（{@code @SaCheckLogin}），
 * 再由 Service 校验「是不是在队成员」，不是则抛 A0301 无权限。
 * 普通注册用户三个接口一律进不去。
 *
 * 这里只挂 {@code @SaCheckLogin} 而不挂 {@code @SaCheckRole("member")}，原因是刻意的：
 * 「成员」不是一个角色标签，而是管理员在 studio_member 表里录的一条档案。
 * 角色可能与档案不同步（比如有人被移出名录但角色没改），
 * 若按角色放行，就绕开了真正的准入规则。所以权限判定统一认表，不认角色。
 * 这也是全项目的一条纪律：角色回答「能进哪个后台」，档案回答「是不是团队成员」。
 *
 * 白名单（SaTokenMvcConfig 里登记「允许匿名访问」的路径清单）里没有任何 /attendance 路径：
 * 考勤是内部管理数据，游客没有查看的理由。因此三个接口走「默认要求登录」，
 * 未登录由拦截器直接返回 A0201，请求根本进不到方法体。
 *
 * Controller 的职责与 UserController 一致：接参、调 Service、包成 BaseResponse，
 * 不写业务分支。唯一的额外工作是在请求线程里把 IP 与 UA 取出来——
 * Service 层刻意不认识 HTTP（这样「给定这些头该判内网还是外网」可以脱离容器直接单测）。
 *
 * 接口地址前缀 /api（来自 server.servlet.context-path），
 * 完整路径形如 http://localhost:8080/api/attendance/check-in。
 *
 * @author shaoshing
 */
@RestController
@RequestMapping("/attendance")
@Tag(name = "考勤模块", description = "工作室成员签到、今日出勤看板与个人历史")
@RequiredArgsConstructor
public class AttendanceController {

    /** Nginx 惯用配置 proxy_set_header X-Real-IP $remote_addr; 对应的头名 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 逐跳追加的代理链，形如 客户端, 代理1, 代理2 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String HEADER_USER_AGENT = "User-Agent";

    private final AttendanceService attendanceService;

    /**
     * 成员签到
     *
     * <p>为什么这样设计：签到主体是当前登录账号，网络信息由服务端从请求里取，
     * 客户端无法通过传参影响「算不算内网」。若允许前端传一个 inLan 字段，
     * 那这个判定的意义就没了——谁都会填 true。判定完全依赖 TCP 对端是否可信：
     * 只有反向代理（Nginx）转发过来的请求，那两个自定义头才值得采信。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 从当前请求对象里取出四样东西：TCP 对端地址、两个代理头、User-Agent。
     *     这一步必须发生在请求线程里，且必须在调用 Service 前——它们属于
     *     ThreadLocal 与 socket 层的东西，一旦进入异步分支就取不到了；</li>
     *     <li>② 把四个原始值交给 Service。注意是「原始值」而不是「解析后的 IP」：
     *     解析规则（哪一层代理可信、内网网段怎么比）属于业务判断，放在 Service 里才能单测；</li>
     *     <li>③ 包成统一响应体。返回的结论（时刻 / 是否内网 / 本月累计）够前端直接刷新页面，
     *     不必再调一次看板接口。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法没有任何 {@code @RequestParam} 或 {@code @RequestBody}——
     * 它不是「忘了写」，而是刻意不接受客户端传参。签到时间、用户身份、内外网判定
     * 全部由服务端决定。若哪天为了「让前端方便」加一个 userId 参数，
     * 那就等于任何人都能替别人签到。
     *
     * @param request 当前请求，由 Spring 注入；只用于读取 IP 与 UA，不读取业务参数
     * @return 本次签到结论：签到时刻、是否判为内网、本月累计次数（含本次）
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 不是工作室成员（A0301）、距上次签到过近（A0401）、入库失败（B0001）时抛出
     */
    @PostMapping("/check-in")
    @SaCheckLogin
    @Operation(summary = "【成员】签到", description = "一天内多次签到受最小间隔限制；是否内网按服务端网段判定")
    public BaseResponse<AttendanceCheckInVO> checkIn(HttpServletRequest request) {
        // ①② 这四个值必须在当前请求线程内取好再传下去：
        // request 的生命周期只到本次请求结束，异步线程里再用它就是错的
        // ③ 包成统一响应体：签到结论够前端直接刷新页面，不必再调一次看板
        return ResultUtils.success(attendanceService.checkIn(
                request.getRemoteAddr(),
                request.getHeader(HEADER_X_REAL_IP),
                request.getHeader(HEADER_X_FORWARDED_FOR),
                request.getHeader(HEADER_USER_AGENT)));
    }

    /**
     * 今日出勤看板
     *
     * <p>为什么这样设计：看板回答的是「今天谁来了」，因此未打卡的成员也要出现在列表里，
     * 用 checkedIn=false 表示。若只返回已打卡的人，管理员就无法一眼看出「谁没来」——
     * 而「谁没来」恰恰是考勤最关心的问题。
     * 响应不含 IP、UA、账号等任何位置或身份信息，只有姓名、时间、次数与内外网标记：
     * 看板是成员之间互相可见的页面，多一个字段就多一处泄露面。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参。看板固定是「今天」，不接受日期参数——允许查任意一天
     *     等于把历史考勤也开放给了所有成员，而那是管理员才该看的数据；</li>
     *     <li>② 调 Service：先确认调用者是在队成员（不是则 A0301），
     *     再用三次聚合查询在内存里合并出看板，而不是「查成员列表后逐人统计」的 N+1；</li>
     *     <li>③ 包成统一响应体。成员列表为空时返回空列表，不会是 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只挂了登录注解，看起来「任何登录用户都能看」，
     * 实际挡在 Service 里。这是有意的分工——成员身份是业务事实，只能查库判断，
     * 注解表达不了。但这也意味着：若有人把 Service 里的成员校验删掉，
     * 这个接口就会对全体注册用户开放，而编译、启动、单测都不会报错。
     *
     * @return 今日看板列表，记录为 AttendanceBoardVO（姓名、打卡时刻、本月次数、内外网标记）；
     * 没有在队成员时返回空列表，不会返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 调用者不是在队成员（A0301）时抛出
     */
    @GetMapping("/board/today")
    @SaCheckLogin
    @Operation(summary = "【成员】今日出勤看板", description = "谁打卡了、几点、本月几次；未打卡成员也列出")
    public BaseResponse<List<AttendanceBoardVO>> getTodayBoard() {
        // ① 无入参：看板固定是「今天」，日期不由请求方决定
        // ②③ 成员校验与聚合查询都在 Service 内，Controller 只负责转发与包装
        return ResultUtils.success(attendanceService.getTodayBoard());
    }

    /**
     * 我的签到历史
     *
     * <p>为什么这样设计：只返回调用者自己的记录，且没有 userId 参数——
     * 「查谁的」由登录态决定，不由请求参数决定。若加了 userId，
     * 「看别人的签到历史」就成了一个功能，而签到记录带有位置信息（内外网标记），
     * 那是隐私。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 无入参，也不接受分页参数；</li>
     *     <li>② 调 Service 取本人最近若干条，按签到时刻倒序，并在 Service 里限制条数——
     *     考勤表随时间持续增长，不加限制迟早会把整表历史拉进内存；</li>
     *     <li>③ 包成统一响应体。没有记录时返回空列表。</li>
     * </ol>
     *
     * <p>容易踩的坑：本接口「查自己」也照样脱敏（不含 IP 与 UA）。
     * 有人会觉得「反正是自己的数据，返回全字段没关系」，但这些响应同样会被
     * 前端缓存、被日志中间件采样打印，多一个敏感字段就多一处泄露面。
     *
     * @return 本人最近的签到记录（按时刻倒序，条数有上限）；无记录时返回空列表
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     * 调用者不是在队成员（A0301）时抛出
     */
    @GetMapping("/me")
    @SaCheckLogin
    @Operation(summary = "【成员】我的签到历史", description = "只返回本人记录，最近若干条")
    public BaseResponse<List<AttendanceMeVO>> getMyHistory() {
        // ① 无入参，也不接受分页参数：条数上限在 Service 里，前端不需要也无法指定
        // ②③ 「查谁的」由登录态决定，因此这里没有任何用户参数可传；结果直接包成响应体
        return ResultUtils.success(attendanceService.getMyHistory());
    }
}

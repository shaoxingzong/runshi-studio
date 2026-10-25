package com.bhu.runshistudioweb.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bhu.runshistudioweb.model.entity.StudioAttendance;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceBoardVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceCheckInVO;
import com.bhu.runshistudioweb.model.vo.attendance.AttendanceMeVO;

import java.util.List;

/**
 * 考勤签到服务接口
 *
 * <p>本接口是考勤模块的「契约」：参数含义、返回值、异常与错误码都写在这里，
 * 实现类只解释「为什么这么实现」。调用方（Controller）只依赖本接口，
 * 换实现、加缓存都不影响上层。
 *
 * <p>准入规则（本模块最重要的产品决策）：
 * 只有在队的工作室成员能签到，也只有他们能看出勤看板；普通注册用户两者都不能。
 * 「是否算成员」的判定权威是 studio_member 表（存在 user_id = 当前登录用户
 * 且 member_status = 0 的记录），不是 sys_user.user_role。
 * 因为「成员」是管理员后台标记出来的身份（往成员表里录一条档案），
 * 而角色只是一个权限标签；两者可能不同步，必须认表。
 *
 * <p>为什么这条规则要写在接口上：Controller 只挂了 {@code @SaCheckLogin}，
 * 真正的成员判定在实现层。若调用方误以为「登录了就能用」，
 * 就会漏掉「非成员要给出引导」的处理——本接口把「非成员一律 A0301」写成契约，
 * 让这个前提无处可藏。
 *
 * <p>非成员调用本接口任何方法都会抛 A0301 无权限，
 * 连「看自己」的入口也不给——他没有签到数据，给了只会是空列表。
 * 空列表看起来像「功能坏了」，比一句明确的「你不是成员」更糟。
 *
 * <p>约定：参数不合法 / 业务不允许时一律抛 BusinessException，
 * 不返回错误码、不返回 null，由全局异常处理器统一转成标准响应。
 * 这样 Controller 里没有一处 if-else 判断错误码，错误码也只有一处定义。
 *
 * <p>继承 {@code IService<StudioAttendance>} 后，单表 CRUD
 * （save / getById / count / page …）直接可用，不必在本接口里再声明一遍。
 * 但业务方法一律走下面三个显式声明的方法：直接调 save 会绕过准入校验。
 *
 * @author shaoshing
 */
public interface AttendanceService extends IService<StudioAttendance> {

    /**
     * 当前登录成员签到
     *
     * <p>为什么这样设计：签到要同时回答两个问题——「谁签的」与「算不算在工作室」。
     * 前者来自登录态，后者来自网络地址，两者的可信来源完全不同，因此不能合并成一个入参。
     * 三个网络相关的入参都由 Controller 从请求上下文取出后传入：
     * 本接口不认识 HTTP（与 ClientIpManager 同一条分层纪律），
     * 保持这样既能在纯单测里直接调用（给定一组头就能验证「该判内网还是外网」），
     * 也避免了 Service 层依赖请求对象。
     *
     * <p>执行步骤（任何一步失败都不会写库）：
     * <ol>
     *     <li>① 校验登录态，未登录抛 A0201。放在最前面是因为后面所有步骤都要用到账号 ID；</li>
     *     <li>② 校验是否在队成员，不是则抛 A0301。必须在查签到记录之前——
     *     非成员本来就没有签到资格，先查再拒等于白花一次数据库往返；</li>
     *     <li>③ 按防抖阈值检查「距上一次签到是否过短」，过短则拒绝（挡误触连点）。
     *     这一步同样必须在写库之前：被拦下的请求不该产生任何副作用；</li>
     *     <li>④ 解析客户端 IP 并判定内外网。放在写库前，是因为判定结果要作为记录的一部分落库；</li>
     *     <li>⑤ 写入一条签到记录，并把本月累计次数回填到返回值。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>不是幂等</b>（重复调用结果不一致）的——
     * 同一分钟连点两次，第二次会被防抖挡下；但间隔超过阈值再签一次就会真的多一条记录。
     * 这是刻意的：签到记录是「到场凭证」，多次到场本就该有多条。
     * 防抖靠 minIntervalSeconds 这个时间阈值，而不是给表加「用户 + 日期」唯一索引——
     * 加唯一索引会让「上午来一次、下午再来一次」的第二次签到直接失败，
     * 而考勤要记录的是「来过几次」，不是「今天来没来」。
     *
     * @param remoteAddr TCP 对端地址（可能带端口，如 192.168.1.5:54321）；
     *                   由 Controller 从请求里取，本层不做任何 HTTP 相关的取值。
     *                   传 null 不会抛异常：解析不出地址时按「外网」处理并落占位串
     * @param xRealIp X-Real-IP 头原始值，可为 null。
     *                只有当 remoteAddr 是可信代理时才会被采信，否则一律忽略——
     *                请求头是客户端可伪造的，无条件采信等于让任何人自称「在工作室」
     * @param xForwardedFor X-Forwarded-For 头原始值，可为 null。
     *                      同样只在 remoteAddr 可信时才读取，且从右往左取第一个非代理地址
     * @param userAgent User-Agent 头原始值，可为 null（超长会被截断到列宽 512）。
     *                  客户端可以传几 MB 的 UA，不截断就是一条稳定的入库失败来源
     * @return 本次签到结论（时刻 / 是否内网 / 本月累计次数），永不为 null；
     *         调用方不需要判空，也不必再回头调一次看板接口来刷新页面
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）、
     *         非在队成员（A0301）、签到过于频繁（A0401）、入库失败（B0001）时抛出
     */
    AttendanceCheckInVO checkIn(String remoteAddr, String xRealIp, String xForwardedFor, String userAgent);

    /**
     * 今日出勤看板（仅成员可见）
     *
     * <p>为什么这样设计：看板要回答的是「今天谁来了、几点来的、这个月来了几次」，
     * 因此必须把「全部在队成员」与「今天的签到记录」做一次合并——
     * 只查签到记录会让没来的成员直接消失，用户看到的是「今天只来了 3 个人」，
     * 却无法判断剩下的人是真的没来、还是根本不在名单里。
     *
     * <p>性能上的取舍：内部走三次聚合查询后在内存合并，不是「查成员列表后逐人 count」
     * （那会退化成 N+1：一次主查询引发 N 次子查询，成员 50 人就是 50 次查询）。
     * 三次查询的次数与成员规模无关，成员从几十人涨到几百人也依然成立。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验登录态与在队成员身份，非成员抛 A0301；</li>
     *     <li>② 取在队且<b>已绑定登录账号</b>的成员列表——未绑定的档案没有签到主体，
     *     放进看板只会是一行永远「未打卡」的记录（对应实现里的标注 ①）；</li>
     *     <li>③ 一次聚合查出今日每人「最早一次签到时刻」与「是否出现过内网」（标注 ②）；</li>
     *     <li>④ 一次聚合查出本月每人的签到次数（标注 ③）；</li>
     *     <li>⑤ 在内存里按成员遍历一次拼装，并按「已打卡在前、未打卡在后」排序（标注 ④）。
     *     这一步必须放在所有查询之后：它要用到前三份结果，任何一份还没就绪都拼不出来。</li>
     * </ol>
     *
     * <p>容易踩的坑：inLan 的口径是「当天<b>只要有一次</b>内网记录就算内网」，
     * 不是「最后一次在哪」。若按最后一次判定，一个上午在工作室、下午用手机流量
     * 又签了一次的人会被显示成「外网」，而看板想表达的是「这天他来过工作室」。
     * 另一个坑是排序：未打卡成员的 checkInAt 为 null，比较器若不处理 null 会直接 NPE。
     *
     * @return 今日看板列表，每人一条；成员列表为空时返回空列表，不返回 null。
     *         未打卡的成员同样出现在列表里（checkedIn=false、checkInAt=null），
     *         因此「列表长度」等于「在队且已绑账号的成员数」，不等于「今日签到人数」
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）
     *         或调用者非在队成员（A0301）时抛出
     */
    List<AttendanceBoardVO> getTodayBoard();

    /**
     * 本人签到历史（仅成员可见）
     *
     * <p>为什么这样设计：只返回调用者自己的记录——「看别人签了几次」属于成员之间的
     * 横向比较，产品上没有这个诉求，接口上就不该给这个入口。
     * 排序按签到时刻倒序，并限制在最近若干条：
     * 考勤表随时间持续增长，不加限制迟早会把整表历史拉进内存，
     * 而「最近出勤情况」这个产品诉求用最近几十条就足够回答。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>① 校验登录态与在队成员身份，非成员抛 A0301；</li>
     *     <li>② 按「当前账号 + 签到时刻倒序」取一页（页大小即历史条数上限）。
     *     这里用分页而不是「查全部再截取」：后者会把全部历史读进内存再丢掉；</li>
     *     <li>③ 逐条转成 VO，并按 in_lan 列还原布尔标记。</li>
     * </ol>
     *
     * <p>容易踩的坑：排序必须带次级键（记录 ID），只按签到时刻排序是不够的——
     * check_in_at 是秒级精度，同一秒内的两条记录时间完全相同，
     * 只按它排序会让这两条的先后在多次查询间漂移，表现为「翻页时重复或漏条」。
     *
     * @return 本人历史列表，按签到时刻倒序、最多最近若干条；
     *         无记录时返回空列表，不返回 null
     * @throws com.bhu.runshistudioweb.exception.BusinessException 未登录（A0201）
     *         或调用者非在队成员（A0301）时抛出
     */
    List<AttendanceMeVO> getMyHistory();
}

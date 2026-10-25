package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AttendanceProperties;
import com.bhu.runshistudioweb.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析与内外网判定（Manager 层）
 *
 * 分层纪律（与 manager/FileManager 一致）：本类不认识 HTTP——
 * 不注入 HttpServletRequest，只接收字符串参数。这样它既可以被 Controller 调用，
 * 也能在 MockMvc 单测、批量校验脚本里直接使用，且所有边界情况都能用纯单测覆盖，
 * 不必为「怎么伪造一个请求对象」浪费成本。
 * 因此「在请求线程里取 IP」这件事由调用方负责（见 AttendanceController）：
 * 必须在请求线程内取 SaHolder.getRequest().getSource() 之类的值再传进来。
 *
 * 信任链：为什么默认「谁都不信」
 *
 * 判断一个 IP 是否可信，只有两个信息源：TCP 对端地址（内核提供，
 * 无法伪造）与请求头（客户端完全可控）。因此规则只有一条：
 *
 * 只有当 TCP 对端地址本身是可信代理时，才采信它带来的请求头；否则一律忽略所有头。
 *
 * 反向理解更容易记住：如果不加这道判断，任何人在家打开浏览器开发者工具，
 * 加一个 X-Real-IP: 192.168.1.5 就能让自己「在工作室签到」——整套考勤的区分能力归零。
 *
 * XFF 的取数方向：从右往左，不是从左往右
 *
 * X-Forwarded-For 的格式是逐跳追加的：
 *  X-Forwarded-For: <客户端声称的来源>, <第一跳代理>, <第二跳代理>
 *
 * 常见误区是「取最左边那个，因为它是原始客户端」。这在代理只做覆盖时成立，
 * 但绝大多数 Nginx 配置用的是 $proxy_add_x_forwarded_for，它的语义是追加：
 * 客户端自己带的 X-Forwarded-For: 1.2.3.4 会被原样保留在最左侧。
 * 于是「取最左」等于把伪造权直接交给了客户端——攻击者只要发
 * X-Forwarded-For: 192.168.1.5，取到的就是内网地址。
 *
 * 因此本类从右往左扫描：跳过所有可信代理，遇到的第一个非可信地址才是真实客户端。
 * 这个方向不依赖代理是「覆盖」还是「追加」，两种配置都正确。
 *
 * 为什么不用 Spring 的 forward-headers-strategy
 *
 * server.forward-headers-strategy=NATIVE/FRAMEWORK 会让框架无条件用请求头
 * 改写 request.getRemoteAddr()。它的问题不是实现有 bug，而是缺少信息来源的判断——
 * 它无法表达「只信任来自 127.0.0.1 的头」这层语义，等于把所有客户端都当成可信代理。
 * 本项目需要的是「按来源分级信任」，所以自己做，而不是打开那个开关。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class ClientIpManager {

    /**
     * 构造器注入而非字段注入：既符合 Spring 的推荐实践，也让本类能脱离 Spring 容器
     * 在纯单测里直接 new——这是「本类不认识 HTTP、只收字符串」这条分层纪律的
     * 另一半，否则要验证一个网段判定就得先 Mock 出整个请求上下文
     */
    private final AttendanceProperties attendanceProperties;

    /** 预解析后的可信代理网段（避免每个请求都做字符串解析） */
    private List<IpUtils.Cidr> trustedProxyCidrs = new ArrayList<>();

    /** 预解析后的局域网网段 */
    private List<IpUtils.Cidr> lanCidrs = new ArrayList<>();

    /**
     * 创建实例，并把配置里的网段文本预先解析成可比较的对象
     *
     * <p>为什么这样设计：网段判定在每个请求上都要做，而「把 {@code 192.168.1.0/24}
     * 解析成字节掩码」是纯字符串计算、与请求无关。把它挪到构造阶段，
     * 每个请求就只剩几次字节比较。若放在 {@link #isInLan(String)} 里边用边解析，
     * 一次签到要重复解析同一份配置，纯属浪费。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>保存配置对象，供日志与提示文案读取原始文本；</li>
     *     <li>调用 {@link #init()} 完成预解析——<b>写在构造器里而不是靠
     *     {@code @PostConstruct}</b>：纯单测直接 {@code new ClientIpManager(props)} 时
     *     容器根本不存在，{@code @PostConstruct} 不会被调用，网段列表就会是空的，
     *     于是「所有地址都判外网」这种假阴性会混进单测结论里，且不报任何错。</li>
     * </ol>
     *
     * <p>容易踩的坑：构造器里做初始化意味着「解析失败」也只能告警、不能抛异常。
     * 一旦在这里抛，应用会因为一条网段配置写错而起不来——考勤只是附加能力，
     * 不该有这种否决权。
     *
     * @param attendanceProperties 考勤配置，不能为 null（Spring 注入保证）；
     *                             传 null 会在构造器第一行就 NPE，属于启动期错误，
     *                             比运行期悄悄不判定要好
     */
    public ClientIpManager(AttendanceProperties attendanceProperties) {
        this.attendanceProperties = attendanceProperties;
        // 构造即完成预解析：Spring 容器与纯单测走的是同一条初始化路径，
        // 不会出现「单测里忘了调 init，于是网段为空、判定全是外网」的假阴性
        init();
    }

    /**
     * 解析配置里的两组网段，并把每一条非法配置当场喊出来
     *
     * <p>为什么这样设计：网段配置写错（少写一位、用 IPv6 网段去配 IPv4 地址、
     * 前缀长度越界）的后果是「所有人都被判外网」，接口不报错、日志也没有异常，
     * 只有等到考勤报表出来才会发现——这是最难定位的一类故障。
     * 因此这里选择在启动阶段逐条打印非法项，让问题在部署时暴露，而不是事后靠猜。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>解析可信代理网段：它决定「请求头能不能被采信」，属于安全边界，必须先就位；</li>
     *     <li>解析局域网网段：它决定「算不算在工作室」，属于业务口径；</li>
     *     <li>分别对两组结果做空值告警。顺序上先报局域网、再报可信代理：
     *     前者缺失会让所有签到记为外网，是业务失准；后者缺失在「无反向代理」的部署下完全正常，
     *     只是 Nginx 之后部署时同样会全判外网，两者严重程度不同，日志分开写便于定位。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法被声明成 public，于是它可以被再次调用（例如配置热更新后重解析）。
     * 重解析是整体替换而不是追加，因此不会出现网段越调越多的情况；
     * 但也意味着调用期间若有请求正在遍历旧列表，它看到的是旧快照——这里接受这一点，
     * 因为网段配置变更本就该在低峰期做，且最坏结果只是短暂按旧网段判定。
     *
     * @see #isInLan(String) 网段为空时该方法恒返回 false，即全部判外网
     */
    public void init() {
        trustedProxyCidrs = parseAll(attendanceProperties.getTrustedProxies(), "可信代理（studio.attendance.trusted-proxies）");
        lanCidrs = parseAll(attendanceProperties.getLanCidrs(), "局域网网段（studio.attendance.lan-cidrs）");

        if (lanCidrs.isEmpty()) {
            // 不阻止启动：考勤是附加能力，配置缺失不该让整个站点起不来。
            // 但这条 warn 必须显式可见——它的后果是「所有签到都记为外网」。
            log.warn("未配置有效的局域网网段（studio.attendance.lan-cidrs={}），"
                            + "所有签到都会被判定为外网。请在工作室电脑上执行 ipconfig 取得网段后配置，例如 192.168.1.0/24",
                    attendanceProperties.getLanCidrs());
        } else {
            log.info("考勤内外网判定已启用 | 局域网网段={} | 可信代理={}", describe(lanCidrs), describe(trustedProxyCidrs));
        }

        if (trustedProxyCidrs.isEmpty()) {
            log.warn("未配置有效的可信代理（studio.attendance.trusted-proxies={}），"
                            + "所有请求的 X-Real-IP / X-Forwarded-For 都会被忽略，"
                            + "直接按 TCP 对端地址判定。若部署在 Nginx 之后，这会导致所有签到都记为外网",
                    attendanceProperties.getTrustedProxies());
        }
    }

    /**
     * 判断这次请求到底是谁发来的，并返回一个可信的客户端地址
     *
     * <p>为什么这样设计：判断来源只有两类信息——TCP 对端地址（内核提供，客户端改不了）
     * 与请求头（客户端可以随便写）。两者的可信度天差地别，因此规则只有一条：
     * <b>只有对端本身是可信代理时，才采信它带来的头；否则一律忽略所有头。</b>
     * 去掉这道判断，任何人加一个 {@code X-Real-IP: 192.168.1.5} 就能让自己「在工作室签到」，
     * 考勤的区分能力直接归零。
     *
     * <p>取值优先级刻意排成「对端 → X-Real-IP → XFF → 对端」：
     * 可信时先看 X-Real-IP，因为 Nginx 的惯用配置
     * {@code proxy_set_header X-Real-IP $remote_addr;} 是<b>覆盖</b>语义，客户端传什么都盖掉；
     * 而 XFF 通常被配置成<b>追加</b>，最左侧可能是客户端自己伪造的，必须额外筛选（见 {@link #pickFromXff(String)}）。
     * 所以把更可靠的 X-Real-IP 排在前面，能省掉一次 XFF 扫描。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>归一化对端地址。拿不到就返回 null 而不是猜一个——调用方要据此决定「按未知处理」；</li>
     *     <li>对端不是可信代理时直接返回对端。<b>这一步必须排在任何读头动作之前</b>：
     *     先读头再判断对端，等于把不可信数据先拿进流程，后面很容易顺手用上；</li>
     *     <li>对端可信，尝试 X-Real-IP：覆盖语义，最可靠；</li>
     *     <li>再尝试 XFF：从右往左挑第一个非代理地址；</li>
     *     <li>两个头都没给出可用地址时退回对端。此时返回值其实是代理自己的 IP，
     *     属于配置缺失的表现，不是代码缺陷。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要指望「对端可信」就能随便信头。可信代理这个身份只说明
     * 「这一跳是干净的」，不代表它带来的内容也干净——例如 Nginx 用了
     * {@code $proxy_add_x_forwarded_for} 时，XFF 最左边仍是客户端写的原值。
     * 所以第 ④ 步必须从右往左扫，不能取第一个。
     *
     * @param remoteAddr TCP 对端地址（调用方从请求上下文取；可能带端口，
     *                   例如 {@code 192.168.1.5:54321}，内部会剥掉）
     * @param xRealIp X-Real-IP 头的原始值，可为 null；为 null 或无法解析时跳过，
     *                不影响后续的 XFF 尝试
     * @param xForwardedFor X-Forwarded-For 头的原始值，可为 null；
     *                      只有对端可信时才会被读取
     * @return 归一化后的客户端 IP；对端无法解析时返回 null。
     *         调用方必须处理 null（考勤里落库为占位串 {@code unknown}），
     *         绝不能把 null 当成「内网」
     */
    public String resolveClientIp(String remoteAddr, String xRealIp, String xForwardedFor) {
        String peer = IpUtils.normalize(remoteAddr);
        if (peer == null) {
            // 对端都拿不到（非常规容器 / 特殊传输层），不再猜测，交给调用方兜底
            log.debug("无法解析 TCP 对端地址，请求头不予采信 | remoteAddr={}", remoteAddr);
            return null;
        }

        if (!isTrustedProxy(peer)) {
            // 安全根基：非可信来源，直接忽略它带来的所有头。
            // 这条分支同时也是「应用直接暴露、没有反向代理」时的正常路径
            log.debug("对端非可信代理，忽略请求头 | peer={} | xRealIp={} | xff={}", peer, xRealIp, xForwardedFor);
            return peer;
        }

        String realIp = IpUtils.normalize(xRealIp);
        if (realIp != null) {
            // X-Real-IP 是覆盖语义，可信代理写进去的一定是它看到的真实对端，优先采信
            log.debug("采信 X-Real-IP | peer={} | realIp={}", peer, realIp);
            return realIp;
        }

        String fromXff = pickFromXff(xForwardedFor);
        if (fromXff != null) {
            log.debug("采信 X-Forwarded-For | peer={} | realIp={}", peer, fromXff);
            return fromXff;
        }

        // 可信代理但没带来任何可用头：退回对端地址（此时对端是代理自己，
        // 结果是「代理的 IP」而不是真实客户端——这是配置缺失的表现，不是代码问题）
        log.debug("可信代理未提供有效的 X-Real-IP / X-Forwarded-For，退回对端 | peer={}", peer);
        return peer;
    }

    /**
     * 判断这个地址是否落在工作室的局域网里
     *
     * <p>为什么这样设计：网段判定只对「已配置的网段」负责，不做任何地理或运营商层面的猜测。
     * 命中任意一条配置即算内网——工作室可能同时有有线网段、Wi-Fi 网段与实验室网段，
     * 只认第一条会让换了 Wi-Fi 的成员被判成外网。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先把地址解析成字节。解析失败一律返回 false（外网），
     *     这是<b>刻意选定的失败方向</b>：把未知地址当内网等于给所有人发通行证，
     *     当外网最坏只是记录不准，代价小得多；</li>
     *     <li>逐个网段比对，命中即返回 true。命中就短路返回，不再看剩下的网段——
     *     判定结果已经确定，继续比只是浪费；</li>
     *     <li>全部未命中时返回 false，并把已配置的网段打进 debug 日志，
     *     便于排查「明明在工作室却记成外网」这类问题。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法<b>不认识「内网」这个词的业务含义</b>，它只回答
     * 「是否命中配置」。配置为空时它恒返回 false，看上去像「判定逻辑坏了」，
     * 实际是部署时没填 {@code studio.attendance.lan-cidrs}。
     * 这种情况由 {@link #init()} 的告警日志负责提醒。
     *
     * @param ip 客户端 IP，可为 null（解析失败即返回 false）
     * @return 命中任一配置网段返回 true；未配置网段、IP 非法、或地址族与网段不同时返回 false
     */
    public boolean isInLan(String ip) {
        byte[] addr = IpUtils.toBytes(ip);
        if (addr == null) {
            // 解析不出地址就当外网：方向选错的话，代价是「记录不准」而不是「人人可签到」
            log.debug("IP 非法，判定为外网 | ip={}", ip);
            return false;
        }
        for (IpUtils.Cidr cidr : lanCidrs) {
            if (cidr.matches(addr)) {
                log.debug("判定为内网 | ip={} | 命中网段={}", ip, cidr.source());
                return true;
            }
        }
        log.debug("判定为外网 | ip={} | 已配置网段={}", ip, describe(lanCidrs));
        return false;
    }

    /**
     * 找出这个地址命中的那条局域网网段
     *
     * <p>为什么这样设计：{@link #isInLan(String)} 只回答「在不在」，
     * 但界面上有时需要告诉用户「你当前在哪个网段」，排查配置时也要知道是哪一条生效了。
     * 返回配置原文（{@code source}）而不是网络地址：管理员看到 {@code 192.168.1.0/24}
     * 才能对上自己填的那一行，看到一串字节掩码没有任何意义。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>解析地址，非法直接返回 null（没有网段可谈）；</li>
     *     <li>按配置顺序找第一个命中的网段并返回。顺序即配置顺序，
     *     因此重叠网段（如 {@code 192.168.0.0/16} 与 {@code 192.168.1.0/24}）
     *     以先配置的那条为准——这条规则要记住，否则会奇怪为什么展示的不是更精确的那条。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回 null 有两种含义——「IP 非法」与「不在任何网段内」。
     * 本方法不区分它们，因为调用方（展示层）对两者的处理相同：都不显示网段。
     * 若将来需要区分，应该另开一个方法，而不是让调用方去猜 null 的来源。
     *
     * @param ip 客户端 IP，可为 null
     * @return 命中的网段配置原文；未命中或 IP 非法时返回 null，调用方必须判空
     */
    public IpUtils.Cidr matchLanCidr(String ip) {
        byte[] addr = IpUtils.toBytes(ip);
        if (addr == null) {
            return null;
        }
        for (IpUtils.Cidr cidr : lanCidrs) {
            if (cidr.matches(addr)) {
                return cidr;
            }
        }
        return null;
    }

    /**
     * 判断某个地址是不是可信反向代理
     *
     * <p>为什么这样设计：这是整套 IP 判定的开关。只有返回 true 的地址，
     * 它带来的请求头才会被采信；其余一切来源的头都被丢弃。
     * 默认只有 {@code 127.0.0.1} 与 {@code ::1}，对应「Nginx 与应用同机」这一最常见部署形态——
     * 这个默认值是「最小信任」的体现，而不是图省事。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>解析地址，非法直接返回 false——宁可少信一个代理，也不能因为解析失败而放行；</li>
     *     <li>遍历可信代理网段，命中即返回 true。</li>
     * </ol>
     *
     * <p>容易踩的坑：把整个内网段（如 {@code 192.168.0.0/16}）填进可信代理是危险配置。
     * 那等于宣布「内网里任何一台机器都可以替我改写来源地址」，
     * 工作室里任何一台中招的电脑都能伪造签到。可信代理只该填真正做转发的那一两个地址。
     *
     * @param ip 已归一化的地址，可为 null
     * @return 是可信代理返回 true；未配置可信代理时恒为 false
     */
    public boolean isTrustedProxy(String ip) {
        byte[] addr = IpUtils.toBytes(ip);
        if (addr == null) {
            return false;
        }
        for (IpUtils.Cidr cidr : trustedProxyCidrs) {
            if (cidr.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 X-Forwarded-For 里挑出真实客户端地址（<b>从右往左</b>扫）
     *
     * <p>为什么这样设计：XFF 的格式是逐跳追加的，形如
     * {@code 客户端, 第一跳代理, 第二跳代理}，越靠右越接近「我们自己这一端」。
     * 常见误区是「取最左边那个，因为它是原始客户端」——这在代理做覆盖时成立，
     * 但 Nginx 最常用的 {@code $proxy_add_x_forwarded_for} 是<b>追加</b>语义：
     * 客户端自己带的 {@code X-Forwarded-For: 1.2.3.4} 会被原样保留在最左侧。
     * 于是「取最左」等于把伪造权直接交给了客户端——攻击者发
     * {@code X-Forwarded-For: 192.168.1.5}，取到的就是内网地址。
     * 从右往左扫则不依赖代理是覆盖还是追加，两种配置都正确。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空值直接返回 null，交给调用方退回对端；</li>
     *     <li>按逗号切段，<b>从最后一个下标开始往回走</b>。起点必须是
     *     {@code segments.length - 1}，写成 0 就是上面说的伪造漏洞；</li>
     *     <li>逐段归一化。解析不出来的段（代理拿不到地址时会填 {@code unknown}，
     *     也可能出现带端口的写法）直接跳过而不是让整个解析失败——
     *     一个坏段不该让整条链路丢掉真实地址；</li>
     *     <li>跳过仍是可信代理的段，第一个非可信地址就是答案。</li>
     * </ol>
     *
     * <p>容易踩的坑：如果整条 XFF 全是可信代理（例如多层 Nginx 串联且没有带上客户端），
     * 本方法会返回 null 而不是返回最左边那个代理地址。这是刻意的：
     * 返回代理地址会被误当成客户端，而 null 会让调用方明确退回对端，
     * 语义上「不知道」比「猜一个」更诚实。
     *
     * @param xForwardedFor 头的原始值，可为 null 或空白
     * @return 真实客户端地址；无可用段（空值 / 全部是可信代理）时返回 null
     */
    private String pickFromXff(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        String[] segments = xForwardedFor.split(",");
        // 从右往左：最右侧是离我们最近的一跳，左侧可能是客户端伪造的内容
        for (int i = segments.length - 1; i >= 0; i--) {
            String candidate = IpUtils.normalize(segments[i]);
            if (candidate == null) {
                // 坏段跳过：一个无法解析的段不该让整条 XFF 作废
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 批量解析网段配置，并逐条报出非法项
     *
     * <p>为什么这样设计：配置是一串文本，写错一条不该让其余正确的条目一起失效，
     * 也不该让应用起不来。因此这里采用「跳过坏的、保留好的」策略，
     * 同时把坏的那一条连原文打进 warn 日志——静默跳过才是真正危险的，
     * 那会让「网段配了却没生效」变成一个查不出来的谜。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>配置为 null 时返回空列表（不返回 null，调用方可以省掉判空）；</li>
     *     <li>逐条 trim 后解析；空白条目静默跳过——逗号写多了属于无害的笔误；</li>
     *     <li>解析失败记 warn 并 continue，解析成功才加入结果。</li>
     * </ol>
     *
     * <p>容易踩的坑：这里的「跳过」意味着 {@code 192.168.1.0/24} 写成
     * {@code 192.168.1.0/33} 时，前缀越界会让该条被丢弃，最终行为是
     * 「这条网段不生效」而不是「启动报错」。看到所有人都被判外网时，
     * 第一件事应该是翻启动日志找这条 warn，而不是怀疑判定代码。
     *
     * @param texts 配置原文列表，可为 null
     * @param label 日志用的配置名（如「可信代理」），仅用于让告警能定位到是哪份配置
     * @return 成功解析的网段列表；非法项被跳过，因此可能比入参短
     */
    private List<IpUtils.Cidr> parseAll(List<String> texts, String label) {
        List<IpUtils.Cidr> result = new ArrayList<>();
        if (texts == null) {
            return result;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            IpUtils.Cidr cidr = IpUtils.parseCidr(text);
            if (cidr == null) {
                // 非法配置必须喊出来：它不会让任何请求报错，只会让判定静默偏离预期
                log.warn("{} 中存在无法解析的条目，已跳过：{}", label, text);
                continue;
            }
            result.add(cidr);
        }
        return result;
    }

    /**
     * 把网段列表拼成一行日志可读的文本
     *
     * <p>为什么这样设计：日志里直接打印 {@code List<Cidr>} 会得到 record 的默认
     * {@code toString}，其中网络地址是字节数组（打印出来是对象哈希，不是地址）。
     * 这里改成取每条配置的原文，让日志与配置文件里的写法完全对得上，
     * 排查时可以直接搜索配置行。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>空列表返回 {@code (空)}——这个占位串是刻意的，它能让人一眼看出
     *     「一个都没配上」，而不是误以为日志漏打了；</li>
     *     <li>按顺序取原文拼接，保持与配置文件相同的顺序。</li>
     * </ol>
     *
     * <p>容易踩的坑：本方法只用于日志，返回值不要拿去参与判定或回传给前端——
     * 它是配置原文，可能包含不该对外暴露的内网拓扑信息。
     *
     * @param cidrs 网段列表，可为 null
     * @return 以逗号分隔的配置原文；空列表或 null 返回 {@code (空)}
     */
    private String describe(List<IpUtils.Cidr> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return "(空)";
        }
        List<String> texts = new ArrayList<>(cidrs.size());
        for (IpUtils.Cidr cidr : cidrs) {
            texts.add(cidr.source());
        }
        return String.join(", ", texts);
    }
}

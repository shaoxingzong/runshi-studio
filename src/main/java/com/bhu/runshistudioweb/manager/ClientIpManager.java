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
 * author: shaoshing
 *
 * <p><b>分层纪律（与 {@code manager/FileManager} 一致）</b>：本类<b>不认识 HTTP</b>——
 * 不注入 {@code HttpServletRequest}，只接收字符串参数。这样它既可以被 Controller 调用，
 * 也能在 MockMvc 单测、批量校验脚本里直接使用，且所有边界情况都能用纯单测覆盖，
 * 不必为「怎么伪造一个请求对象」浪费成本。
 * <p>因此「在请求线程里取 IP」这件事由调用方负责（见 {@code AttendanceController}）：
 * 必须在请求线程内取 {@code SaHolder.getRequest().getSource()} 之类的值再传进来。
 *
 * <h2>信任链：为什么默认「谁都不信」</h2>
 *
 * <p>判断一个 IP 是否可信，只有两个信息源：<b>TCP 对端地址</b>（内核提供，
 * 无法伪造）与<b>请求头</b>（客户端完全可控）。因此规则只有一条：
 *
 * <blockquote>
 * <b>只有当 TCP 对端地址本身是可信代理时，才采信它带来的请求头；否则一律忽略所有头。</b>
 * </blockquote>
 *
 * <p>反向理解更容易记住：如果不加这道判断，任何人在家打开浏览器开发者工具，
 * 加一个 {@code X-Real-IP: 192.168.1.5} 就能让自己「在工作室签到」——整套考勤的区分能力归零。
 *
 * <h2>XFF 的取数方向：从右往左，不是从左往右</h2>
 *
 * <p>{@code X-Forwarded-For} 的格式是逐跳追加的：
 * <pre>{@code X-Forwarded-For: <客户端声称的来源>, <第一跳代理>, <第二跳代理>}</pre>
 *
 * <p>常见误区是「取最左边那个，因为它是原始客户端」。这在<b>代理只做覆盖</b>时成立，
 * 但绝大多数 Nginx 配置用的是 {@code $proxy_add_x_forwarded_for}，它的语义是<b>追加</b>：
 * 客户端自己带的 {@code X-Forwarded-For: 1.2.3.4} 会被原样保留在最左侧。
 * 于是「取最左」等于把伪造权直接交给了客户端——攻击者只要发
 * {@code X-Forwarded-For: 192.168.1.5}，取到的就是内网地址。
 *
 * <p>因此本类<b>从右往左</b>扫描：跳过所有可信代理，遇到的第一个非可信地址才是真实客户端。
 * 这个方向不依赖代理是「覆盖」还是「追加」，两种配置都正确。
 *
 * <h2>为什么不用 Spring 的 forward-headers-strategy</h2>
 *
 * <p>{@code server.forward-headers-strategy=NATIVE/FRAMEWORK} 会让框架<b>无条件</b>用请求头
 * 改写 {@code request.getRemoteAddr()}。它的问题不是实现有 bug，而是<b>缺少信息来源的判断</b>——
 * 它无法表达「只信任来自 127.0.0.1 的头」这层语义，等于把所有客户端都当成可信代理。
 * 本项目需要的是「按来源分级信任」，所以自己做，而不是打开那个开关。
 */
@Slf4j
@Component
public class ClientIpManager {

    /**
     * 构造器注入而非字段注入：既符合 Spring 的推荐实践，也让本类能脱离 Spring 容器
     * 在纯单测里直接 {@code new}——这是「本类不认识 HTTP、只收字符串」这条分层纪律的
     * 另一半，否则要验证一个网段判定就得先 Mock 出整个请求上下文
     */
    private final AttendanceProperties attendanceProperties;

    /** 预解析后的可信代理网段（避免每个请求都做字符串解析） */
    private List<IpUtils.Cidr> trustedProxyCidrs = new ArrayList<>();

    /** 预解析后的局域网网段 */
    private List<IpUtils.Cidr> lanCidrs = new ArrayList<>();

    public ClientIpManager(AttendanceProperties attendanceProperties) {
        this.attendanceProperties = attendanceProperties;
        // 构造即完成预解析：Spring 容器与纯单测走的是同一条初始化路径，
        // 不会出现「单测里忘了调 init，于是网段为空、判定全是外网」的假阴性
        init();
    }

    /**
     * 预解析配置并<b>把配置问题显式喊出来</b>（由构造器调用）
     *
     * <p>为什么要 fail-loud：网段写错（少写一位、用了 IPv6 网段配 IPv4 地址、
     * 前缀长度越界）只会表现为「所有人都被判外网」，接口不报任何错、日志也没有异常，
     * 是最难发现的一类问题。这里在启动阶段把每一条非法配置连原文一起打出来，
     * 让它在部署时就被看见，而不是等考勤报表出来才发现。
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
     * 解析客户端真实 IP
     *
     * <p>取值优先级：<b>对端不可信 → 一律用对端；对端可信 → X-Real-IP → XFF（从右往左）→ 对端</b>。
     * 为什么可信时优先 {@code X-Real-IP}：Nginx 的惯用配置
     * {@code proxy_set_header X-Real-IP $remote_addr;} 是<b>覆盖</b>语义，客户端传什么都不生效；
     * 而 XFF 常被配置成追加，需要额外按可信度筛选（这也是它排在后面的原因）。
     *
     * @param remoteAddr   TCP 对端地址（调用方从请求上下文取；可能带端口）
     * @param xRealIp      {@code X-Real-IP} 头的原始值，可为 null
     * @param xForwardedFor {@code X-Forwarded-For} 头的原始值，可为 null
     * @return 归一化后的客户端 IP；<b>拿不到任何有效地址时返回 null</b>
     */
    public String resolveClientIp(String remoteAddr, String xRealIp, String xForwardedFor) {
        String peer = IpUtils.normalize(remoteAddr);
        if (peer == null) {
            // 对端都拿不到（非常规容器 / 特殊传输层），不再猜测，交给调用方兜底
            log.debug("无法解析 TCP 对端地址，请求头不予采信 | remoteAddr={}", remoteAddr);
            return null;
        }

        if (!isTrustedProxy(peer)) {
            // ★ 安全根基：非可信来源，直接忽略它带来的所有头。
            // 这条分支同时也是「应用直接暴露、没有反向代理」时的正常路径
            log.debug("对端非可信代理，忽略请求头 | peer={} | xRealIp={} | xff={}", peer, xRealIp, xForwardedFor);
            return peer;
        }

        String realIp = IpUtils.normalize(xRealIp);
        if (realIp != null) {
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
     * 判断 IP 是否在工作室局域网内
     *
     * <p>未配置任何网段时<b>一律返回 false（外网）</b>：这个方向是安全的那一侧——
     * 把未知地址当内网，等于给所有人发通行证；当外网，最坏只是记录不准。
     *
     * @param ip 客户端 IP（内部会归一化）
     * @return 命中任一配置网段返回 true
     */
    public boolean isInLan(String ip) {
        byte[] addr = IpUtils.toBytes(ip);
        if (addr == null) {
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
     * 返回命中的局域网网段（未命中返回 null）
     *
     * <p>C 端签到页要展示「当前位于哪个网段」，前端拿到的就是这个 source 文本。
     *
     * @param ip 客户端 IP
     * @return 命中的网段配置原文；未命中或 IP 非法返回 null
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
     * 判断某地址是否为可信代理
     *
     * <p>默认只有 {@code 127.0.0.1} 与 {@code ::1}，即「Nginx 与应用同机」这一最常见部署形态。
     *
     * @param ip 已归一化的地址
     * @return 是可信代理返回 true
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
     * 从 XFF 中挑出真实客户端地址
     *
     * <p><b>从右往左</b>扫描、跳过可信代理，第一个非可信地址即为答案（理由见类注释）。
     * 段内可能是 {@code unknown}（部分代理在拿不到地址时会填这个）或带端口的写法，
     * 无法解析的段直接跳过而不是让整个解析失败——一个坏段不该让整条链路丢掉真实地址。
     *
     * @param xForwardedFor 头的原始值
     * @return 真实客户端地址；无可用段返回 null
     */
    private String pickFromXff(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        String[] segments = xForwardedFor.split(",");
        for (int i = segments.length - 1; i >= 0; i--) {
            String candidate = IpUtils.normalize(segments[i]);
            if (candidate == null) {
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
     * @param texts 配置原文列表
     * @param label 日志用的配置名
     * @return 成功解析的网段列表（非法项被跳过，不会让启动失败）
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
     * 把网段列表拼成日志可读文本
     *
     * @param cidrs 网段列表
     * @return 以逗号分隔的原文
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

package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AttendanceProperties;
import com.bhu.runshistudioweb.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析与内外网判定
 *
 * 不注入 HttpServletRequest，只接收字符串参数，因此能脱离容器做纯单测。
 *
 * 信任链只有一条规则：只有 TCP 对端本身是可信代理时，才采信它带来的请求头，否则一律忽略。
 * XFF 从右往左读——Nginx 默认用追加语义，取最左等于把伪造权交给客户端。
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class ClientIpManager {

    /**
     * 构造器注入而非字段注入：本类因此能脱离 Spring 容器，在纯单测里直接 new
     */
    private final AttendanceProperties attendanceProperties;

    /** 预解析后的可信代理网段（避免每个请求都做字符串解析） */
    private List<IpUtils.Cidr> trustedProxyCidrs = new ArrayList<>();

    /** 预解析后的局域网网段 */
    private List<IpUtils.Cidr> lanCidrs = new ArrayList<>();

    /**
     * 创建实例，并把配置里的网段文本预解析成可比较的对象
     *
     * 预解析写在构造器里而不是 {@code @PostConstruct}：纯单测直接 new 时容器不存在，
     * 后者不会被调用，网段列表是空的，「全判外网」的假阴性会混进单测结论且不报错。
     *
     * 构造期解析失败只能告警不能抛异常：一条网段写错不该让应用起不来。
     *
     * @param attendanceProperties 考勤配置，不能为 null
     */
    public ClientIpManager(AttendanceProperties attendanceProperties) {
        this.attendanceProperties = attendanceProperties;
        // 构造即完成预解析：Spring 容器与纯单测走的是同一条初始化路径，
        // 不会出现「单测里忘了调 init，于是网段为空、判定全是外网」的假阴性
        init();
    }

    /**
     * 解析两组网段，并逐条报出非法项
     *
     * 配置写错的后果是「所有人都被判外网」：接口不报错、日志无异常，最难定位，
     * 因此要在启动阶段就把非法项打印出来。
     *
     * 重解析是整体替换而非追加，不会越调越多；调用期间正在遍历旧列表的请求看到的是旧快照。
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
     * 解析出可信的客户端地址
     *
     * 优先级：对端 → X-Real-IP → XFF → 对端。对端不可信时直接返回对端，
     * 且这一步必须排在任何读头动作之前——先读头再判断对端，等于把不可信数据先拿进流程。
     *
     * 坑：对端可信不代表它带来的头可信。Nginx 用 $proxy_add_x_forwarded_for 时
     * XFF 最左仍是客户端写的原值，所以必须从右往左扫。
     *
     * @param remoteAddr TCP 对端地址，可能带端口，内部会剥掉
     * @param xRealIp X-Real-IP 原始值，可为 null
     * @param xForwardedFor X-Forwarded-For 原始值，可为 null，仅对端可信时才读取
     * @return 归一化后的客户端 IP；对端无法解析时返回 null，调用方必须处理
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
     * 判断地址是否落在工作室局域网内（命中任一配置网段即为内网）
     *
     * 解析失败一律返回 false：把未知地址当内网等于给所有人发通行证，当外网最坏只是记录不准。
     * 配置为空时恒为 false，由 {@link #init()} 的告警负责提醒。
     *
     * @param ip 客户端 IP，可为 null
     * @return 命中任一配置网段返回 true；未配置、IP 非法或地址族不同时返回 false
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
     * 找出该地址命中的局域网网段
     *
     * 返回配置原文而不是网络地址：管理员看到 192.168.1.0/24 才能对上自己填的那一行。
     * 重叠网段以先配置的那条为准。
     *
     * @param ip 客户端 IP，可为 null
     * @return 命中的网段；未命中或 IP 非法时返回 null，两种含义本方法不区分
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
     * 判断地址是否可信反向代理（整套 IP 判定的开关）
     *
     * 坑：把整个内网段填进可信代理，等于宣布内网任何一台机器都能改写来源地址。
     * 可信代理只该填真正做转发的那一两个地址。
     *
     * @param ip 已归一化的地址，可为 null
     * @return 是可信代理返回 true；未配置时恒为 false
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
     * 从 X-Forwarded-For 里挑出真实客户端地址，从右往左扫
     *
     * 坑：起点必须是 {@code segments.length - 1}，写成 0 就取到了客户端可伪造的最左段。
     * 坏段跳过而不是让整条失败；整条全是可信代理时返回 null，由调用方退回对端。
     *
     * @param xForwardedFor 头的原始值，可为 null 或空白
     * @return 真实客户端地址；无可用段时返回 null
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
     * 批量解析网段配置，逐条报出非法项
     *
     * 一条写错不该让其余正确条目一起失效，也不该让应用起不来；
     * 但静默跳过更危险，所以坏的那条要连原文打进 warn。
     *
     * @param texts 配置原文列表，可为 null
     * @param label 日志用的配置名
     * @return 成功解析的网段列表，可能比入参短
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
     * 把网段列表拼成一行可读日志（取配置原文，与配置文件写法对得上）
     *
     * 只用于日志：返回值是配置原文，可能含内网拓扑信息，不要回传给前端。
     *
     * @param cidrs 网段列表，可为 null
     * @return 逗号分隔的配置原文；空列表返回 {@code (空)}
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

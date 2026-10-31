package com.bhu.runshistudioweb.manager;

import com.bhu.runshistudioweb.config.AttendanceProperties;
import com.bhu.runshistudioweb.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 IP 解析（只记录，不判定位置）
 *
 * @author shaoshing
 */
@Slf4j
@Component
public class ClientIpManager {

    /** 构造器注入而非字段注入：本类因此能脱离 Spring 容器，在纯单测里直接 new */
    private final AttendanceProperties attendanceProperties;

    /** 预解析后的可信代理网段（避免每个请求都做字符串解析） */
    private List<IpUtils.Cidr> trustedProxyCidrs = new ArrayList<>();

    /**
     * 创建实例，并把配置里的网段文本预解析成可比较的对象
     *
     * 构造期解析失败只能告警不能抛异常：一条网段写错不该让应用起不来。
     *
     * @param attendanceProperties 考勤配置，不能为 null
     */
    public ClientIpManager(AttendanceProperties attendanceProperties) {
        this.attendanceProperties = attendanceProperties;
        init();
    }

    /** 解析可信代理网段，并逐条报出非法项 */
    public void init() {
        trustedProxyCidrs = parseAll(attendanceProperties.getTrustedProxies(), "可信代理（studio.attendance.trusted-proxies）");

        if (trustedProxyCidrs.isEmpty()) {
            log.warn("未配置有效的可信代理（studio.attendance.trusted-proxies={}），"
                            + "所有请求的 X-Real-IP / X-Forwarded-For 都会被忽略，"
                            + "签到记录的 IP 将退化为 TCP 对端地址（通常是 Nginx）",
                    attendanceProperties.getTrustedProxies());
        } else {
            log.info("客户端 IP 解析已启用 | 可信代理={}", describe(trustedProxyCidrs));
        }
    }

    /**
     * 解析出可信的客户端地址
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

        log.debug("可信代理未提供有效的 X-Real-IP / X-Forwarded-For，退回对端 | peer={}", peer);
        return peer;
    }

    /**
     * 判断地址是否可信反向代理（IP 解析的开关）
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

    /** 从 X-Forwarded-For 里挑出真实客户端地址，从右往左扫 */
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

    /** 批量解析网段配置，逐条报出非法项 */
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

    /** 把网段列表拼成一行可读日志（取配置原文，与配置文件写法对得上） */
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

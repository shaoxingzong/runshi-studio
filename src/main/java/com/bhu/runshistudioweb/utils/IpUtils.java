package com.bhu.runshistudioweb.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 地址处理工具（纯静态、无 Spring 依赖、可直接单测）
 *
 * author: shaoshing
 *
 * <p>只做三件事：<b>归一化</b>、<b>解析 CIDR</b>、<b>判断某 IP 是否落在某网段内</b>。
 * 业务判定（内外网、信任链）不在这里，见 {@code manager/ClientIpManager}。
 *
 * <p><b>为什么必须归一化，而不是拿到字符串直接比较</b>——同一个客户端地址会有多种写法：
 * <table border="1">
 *     <caption>同一地址的不同形态</caption>
 *     <tr><th>来源</th><th>实际取值</th></tr>
 *     <tr><td>直连（IPv4）</td><td>{@code 192.168.1.5}</td></tr>
 *     <tr><td>直连（带端口，某些容器/日志格式）</td><td>{@code 192.168.1.5:54321}</td></tr>
 *     <tr><td>Nginx 监听双栈时转发的 IPv4</td><td>{@code ::ffff:192.168.1.5}</td></tr>
 *     <tr><td>IPv6 环回的完整写法</td><td>{@code 0:0:0:0:0:0:0:1} 而非 {@code ::1}</td></tr>
 * </table>
 * 若不归一化，配置里写 {@code 192.168.1.0/24} 将匹配不上 {@code ::ffff:192.168.1.5}，
 * 结果是「明明在工作室却记为外网」——而且完全不报错。
 *
 * <p><b>为什么不用 {@code InetAddress.getByName(String)} 来解析</b>（本类最重要的纪律）：
 * 那个方法对<b>非字面量</b>输入会走 DNS 解析。攻击者只要传一个 {@code X-Real-IP: abc.example.com}
 * 就能让服务端发起一次 DNS 查询——既是性能问题（每次请求一次外部查询），
 * 也是 SSRF 面（可以让服务器去解析任意域名）。因此本类<b>完全自己解析</b>，
 * 只在最后用 {@code InetAddress.getByAddress(byte[])} 格式化输出（该重载不做任何网络查询）。
 *
 * <p><b>IPv4-mapped IPv6 的归一化规则</b>：{@code ::ffff:a.b.c.d} 这类地址在语义上
 * 就是 IPv4 地址（RFC 4291 的 IPv4-mapped 形式），因此本类把它<b>降级成 4 字节的 IPv4</b>。
 * 这是让「IPv6 监听下的 IPv4 客户端」也能正确匹配 IPv4 网段的关键一步。
 * 注意与之形近的 {@code ::a.b.c.d}（IPv4-compatible，已废弃）<b>不做转换</b>——
 * 它没有 {@code ffff} 标记，是另一个历史遗留形式，混进来会造成误判。
 */
public final class IpUtils {

    private IpUtils() {
    }

    // ==================== 归一化 ====================

    /**
     * 归一化 IP：剥离端口、统一 IPv6 压缩写法、把 IPv4-mapped 降级为 IPv4
     *
     * @param raw 原始地址，可为 null、可带端口、可为任意 IPv6 写法
     * @return 规范化的点分十进制（IPv4）或 RFC 5952 压缩形式（IPv6）；
     *         <b>无法识别时返回 {@code null}</b>——调用方必须处理 null，不能当成「内网」
     */
    public static String normalize(String raw) {
        byte[] bytes = parse(raw);
        return bytes == null ? null : format(bytes);
    }

    /**
     * 解析为地址字节数组（IPv4 为 4 字节，IPv6 为 16 字节）
     *
     * @param ip IP 字符串（可带端口、可为任意等价写法）
     * @return 地址字节；非法输入返回 {@code null}
     */
    public static byte[] toBytes(String ip) {
        return parse(ip);
    }

    /**
     * 是否为合法 IP（归一化后非空即合法）
     *
     * @param ip 待判断的字符串
     * @return 合法返回 true
     */
    public static boolean isValid(String ip) {
        return parse(ip) != null;
    }

    // ==================== CIDR ====================

    /**
     * 解析网段：支持 {@code 192.168.1.0/24}、{@code 10.0.0.7}（裸 IP 视为满位长）、
     * {@code 2001:db8::/32}
     *
     * <p><b>容错设计</b>：{@code 192.168.1.5/24} 这种「网络地址不规整」的写法会被接受，
     * 内部自动把主机位清零得到 {@code 192.168.1.0/24}。这不是纵容写错，
     * 而是因为配置者往往直接复制本机 IP 再补掩码，拒绝它只会换来一次「配置看着没问题却不生效」的排查。
     *
     * @param cidrText 网段文本
     * @return 解析结果；非法输入（前缀越界、地址非法）返回 {@code null}
     */
    public static Cidr parseCidr(String cidrText) {
        if (cidrText == null) {
            return null;
        }
        String text = cidrText.trim();
        if (text.isEmpty()) {
            return null;
        }

        int slash = text.lastIndexOf('/');
        String ipPart = slash >= 0 ? text.substring(0, slash) : text;

        byte[] addr = parse(ipPart);
        if (addr == null) {
            return null;
        }

        int maxBits = addr.length * 8;
        int prefixLength = maxBits;
        if (slash >= 0) {
            String prefixText = text.substring(slash + 1).trim();
            if (prefixText.isEmpty()) {
                return null;
            }
            try {
                prefixLength = Integer.parseInt(prefixText);
            } catch (NumberFormatException e) {
                // 例如写成 /abc、/24.5；不做兜底猜测，直接判非法
                return null;
            }
            // 前缀长度越界必须拒绝：/33（IPv4）或 /129（IPv6）会让掩码计算产生负数移位
            if (prefixLength < 0 || prefixLength > maxBits) {
                return null;
            }
        }

        return new Cidr(text, maskBits(addr, prefixLength), prefixLength);
    }

    /**
     * 判断 IP 是否落在指定网段内
     *
     * @param ip   IP（会自动归一化，带端口 / IPv4-mapped 都能正确匹配）
     * @param cidr 已解析的网段
     * @return 命中返回 true；ip 非法、网段为 null、或两者地址族不同（IPv4 vs IPv6）时返回 false
     */
    public static boolean matches(String ip, Cidr cidr) {
        if (cidr == null) {
            return false;
        }
        byte[] addr = parse(ip);
        return addr != null && cidr.matches(addr);
    }

    /**
     * 判断 IP 是否落在<b>任意一个</b>网段内（配置里多个网段用逗号分隔，命中其一即算内网）
     *
     * @param ip    IP
     * @param cidrs 配置中的网段文本列表，可为 null
     * @return 命中任意一个返回 true
     */
    public static boolean isInAnyCidr(String ip, List<String> cidrs) {
        if (ip == null || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        byte[] addr = parse(ip);
        if (addr == null) {
            return false;
        }
        for (String text : cidrs) {
            Cidr cidr = parseCidr(text);
            if (cidr != null && cidr.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 解析实现 ====================

    /**
     * 核心解析：剥离端口 → 判定地址族 → 解析字节 → IPv4-mapped 降级
     *
     * @param raw 原始地址
     * @return 4 或 16 字节；非法返回 null
     */
    private static byte[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }

        s = stripPort(s);
        if (s == null || s.isEmpty()) {
            return null;
        }

        byte[] bytes;
        if (s.indexOf(':') >= 0) {
            bytes = parseIpv6(s);
        } else {
            bytes = parseIpv4(s);
        }
        if (bytes == null) {
            return null;
        }

        // IPv4-mapped（::ffff:a.b.c.d）在语义上就是 IPv4，降级成 4 字节，
        // 否则配置里的 IPv4 网段永远匹配不上双栈监听转发的地址
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return bytes;
    }

    /**
     * 剥离端口后缀
     *
     * <p>三种形态要分开处理，不能简单按冒号切：
     * <ul>
     *     <li>{@code [::1]:8080} → 方括号形式，取括号内；</li>
     *     <li>{@code 192.168.1.5:8080} → 恰好一个冒号且冒号前含点，判定为 IPv4 带端口；</li>
     *     <li>{@code ::1} / {@code 2001:db8::1} → 冒号多于一个，是 IPv6 本体，<b>不能切</b>
     *     （切了就只剩 {@code ::} 或 {@code 2001} 这类错误地址）。</li>
     * </ul>
     *
     * @param s 去空格后的原始串
     * @return 去掉端口的地址部分；格式明显非法时返回 null
     */
    private static String stripPort(String s) {
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                return null;
            }
            return s.substring(1, close);
        }
        int firstColon = s.indexOf(':');
        if (firstColon < 0) {
            return s;
        }
        // 只有一个冒号、且冒号前是 IPv4 形态，才认定为「IPv4:端口」
        if (s.indexOf(':', firstColon + 1) < 0 && s.lastIndexOf('.', firstColon) >= 0) {
            return s.substring(0, firstColon);
        }
        return s;
    }

    /**
     * 解析 IPv4：严格要求四段、每段 0~255 的纯数字
     *
     * <p>刻意不接受 {@code 0x10.1.1.1}、{@code 010.1.1.1} 这类历史写法
     * （部分系统按八进制/十六进制解释，会造成同一字符串在不同实现下解析出不同地址，
     * 是经典的绕过手法）。只认十进制，且拒绝超过 3 位的段。
     *
     * @param s 点分十进制串
     * @return 4 字节；非法返回 null
     */
    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /**
     * 解析 IPv6：支持 {@code ::} 压缩、尾部 IPv4 写法（{@code ::ffff:1.2.3.4}）、
     * 以及 {@code %zone} 后缀（形如 {@code fe80::1%eth0} 的链路本地地址）
     *
     * @param input IPv6 串
     * @return 16 字节；非法返回 null
     */
    private static byte[] parseIpv6(String input) {
        String s = input;

        // 链路本地的 zone id（%eth0）不参与地址运算，剥离后再解析
        int percent = s.indexOf('%');
        if (percent >= 0) {
            s = s.substring(0, percent);
        }
        if (s.isEmpty()) {
            return null;
        }

        // 尾部 IPv4 写法：先换算成两个十六进制组，后续按统一逻辑处理
        if (s.indexOf('.') >= 0) {
            int lastColon = s.lastIndexOf(':');
            if (lastColon < 0) {
                return null;
            }
            byte[] v4 = parseIpv4(s.substring(lastColon + 1));
            if (v4 == null) {
                return null;
            }
            int high = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
            int low = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
            s = s.substring(0, lastColon + 1) + Integer.toHexString(high) + ":" + Integer.toHexString(low);
        }

        int compressionAt = s.indexOf("::");
        boolean compressed = compressionAt >= 0;
        String left;
        String right;
        if (compressed) {
            // 一个地址里只允许出现一次 "::"，出现两次无从判断零段位置
            if (s.indexOf("::", compressionAt + 1) >= 0) {
                return null;
            }
            left = s.substring(0, compressionAt);
            right = s.substring(compressionAt + 2);
        } else {
            left = s;
            right = "";
        }

        List<Integer> groups = new ArrayList<>(8);
        if (!left.isEmpty()) {
            for (String part : left.split(":", -1)) {
                Integer group = parseHexGroup(part);
                if (group == null) {
                    return null;
                }
                groups.add(group);
            }
        }
        int leftCount = groups.size();

        List<Integer> rightGroups = new ArrayList<>(8);
        if (!right.isEmpty()) {
            for (String part : right.split(":", -1)) {
                Integer group = parseHexGroup(part);
                if (group == null) {
                    return null;
                }
                rightGroups.add(group);
            }
        }

        int total = leftCount + rightGroups.size();
        if (compressed) {
            // "::" 至少要省略一组零；省略 0 组（如 1:2:3:4:5:6:7:8::）在标准里是非法的
            if (total >= 8) {
                return null;
            }
        } else if (total != 8) {
            // 没有 "::" 时必须是完整 8 组
            return null;
        }

        byte[] out = new byte[16];
        int idx = 0;
        for (int group : groups) {
            out[idx++] = (byte) (group >> 8);
            out[idx++] = (byte) (group & 0xFF);
        }
        // 中间的被压缩零段：数组初始值本就是 0，跳过对应字节即可
        idx += (8 - total) * 2;
        for (int group : rightGroups) {
            out[idx++] = (byte) (group >> 8);
            out[idx++] = (byte) (group & 0xFF);
        }
        return out;
    }

    /**
     * 解析单个十六进制组（1~4 个十六进制字符）
     *
     * @param part 单个组
     * @return 0~65535 的整数；非法返回 null
     */
    private static Integer parseHexGroup(String part) {
        if (part.isEmpty() || part.length() > 4) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            int digit = Character.digit(part.charAt(i), 16);
            if (digit < 0) {
                return null;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * 是否为 IPv4-mapped IPv6（前 10 字节全 0，第 11、12 字节为 0xFF）
     *
     * @param bytes 16 字节地址
     * @return 是则返回 true
     */
    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    /**
     * 把地址的主机位清零，得到网络地址
     *
     * @param addr   原地址字节
     * @param prefix 前缀长度
     * @return 新的网络地址字节（不修改入参）
     */
    private static byte[] maskBits(byte[] addr, int prefix) {
        byte[] out = addr.clone();
        int fullBytes = prefix / 8;
        int remainBits = prefix % 8;
        if (remainBits > 0) {
            int mask = (0xFF << (8 - remainBits)) & 0xFF;
            out[fullBytes] = (byte) (out[fullBytes] & mask);
        }
        int start = fullBytes + (remainBits > 0 ? 1 : 0);
        for (int i = start; i < out.length; i++) {
            out[i] = 0;
        }
        return out;
    }

    /**
     * 格式化为展示/存库字符串：IPv4 用点分十进制，IPv6 用 RFC 5952 压缩形式
     *
     * <p>为什么自己做 IPv6 压缩而不调 {@code InetAddress.getHostAddress()}：
     * 后者的输出格式（是否压缩、零段压缩位置）在不同 JDK 版本间并无契约保证，
     * 一旦变了，存库字符串与配置字符串就对不上，排查时非常难定位。自己实现反而稳定。
     *
     * @param bytes 4 或 16 字节地址
     * @return 规范化字符串
     */
    private static String format(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        return formatIpv6(bytes);
    }

    /**
     * IPv6 压缩格式化：把<b>最长的一段连续零组</b>（长度 ≥ 2）替换为 {@code ::}
     *
     * <p>只压缩最长的一段，而不是见零就压：{@code 2001:0:0:1:0:0:0:1} 若压成
     * {@code 2001::1:0:0:0:1} 会增加歧义（RFC 5952 明确要求只压最长段）。
     *
     * @param bytes 16 字节地址
     * @return 压缩后的字符串
     */
    private static String formatIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }

        int bestStart = -1;
        int bestLength = 0;
        int index = 0;
        while (index < 8) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < 8 && groups[end] == 0) {
                end++;
            }
            if (end - index > bestLength) {
                bestLength = end - index;
                bestStart = index;
            }
            index = end;
        }
        // 只压缩长度 ≥ 2 的零段：单独一个零组写成 "0" 比 "::" 更短也更易读
        if (bestLength < 2) {
            bestStart = -1;
        }

        String[] parts = new String[8];
        for (int i = 0; i < 8; i++) {
            parts[i] = Integer.toHexString(groups[i]);
        }

        if (bestStart < 0) {
            return String.join(":", parts);
        }

        // "::" 必须作为一个整体出现：左侧组之后、右侧组之前各补一个冒号，两侧都允许为空，
        // 因此**不能靠「先 append 单冒号、最后裁掉尾部冒号」来实现**。
        // 早先正是那样写的，结果零段在开头（::1 → ":1"）或末尾（2001:db8:: → "2001:db8:"）
        // 都会少一个冒号，产出的是一个**无法再被本类解析**的非法串。
        // 后果很具体：::1 是 trusted-proxies 的默认值之一，归一化一旦输出 ":1"，
        // 后续 isTrustedProxy(":1") 解析失败，IPv6 环回请求就永远不命中可信代理——
        // 且不报任何错，只是「代理带来的真实 IP 被静默忽略」
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bestStart; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(parts[i]);
        }
        sb.append("::");
        for (int i = bestStart + bestLength; i < 8; i++) {
            sb.append(parts[i]);
            if (i < 7) {
                sb.append(':');
            }
        }
        return sb.toString();
    }

    // ==================== 网段对象 ====================

    /**
     * 已解析的网段
     *
     * @param source       原始配置文本（仅用于日志，便于定位是哪条配置命中的）
     * @param network      网络地址字节（主机位已清零）
     * @param prefixLength 前缀长度（IPv4 为 0~32，IPv6 为 0~128）
     */
    public record Cidr(String source, byte[] network, int prefixLength) {

        /**
         * 判断地址是否落在本网段内
         *
         * <p><b>地址族必须相同</b>：IPv4（4 字节）与 IPv6（16 字节）直接判定为不匹配，
         * 而不是先把 IPv4 展开成 {@code ::ffff:} 再比——那会让「IPv6 网段误匹配大量 IPv4 地址」。
         *
         * @param addr 地址字节（4 或 16 字节）
         * @return 命中返回 true
         */
        public boolean matches(byte[] addr) {
            if (addr == null || addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remainBits > 0) {
                int mask = (0xFF << (8 - remainBits)) & 0xFF;
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        }
    }
}

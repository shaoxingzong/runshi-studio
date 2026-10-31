package com.bhu.runshistudioweb.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 地址与 CIDR 网段的解析、归一化与匹配
 *
 * 全程自己解析，不用 {@code InetAddress.getByName}：它对非字面量输入会走 DNS，
 * 客户端可控的请求头就能让服务端发起外部查询。非法输入返回 null 而不抛异常。
 *
 * @author shaoshing
 */
public final class IpUtils {

    private IpUtils() {
    }

    // 归一化

    /**
     * 把任意写法的 IP 归一化成唯一的标准写法
     *
     * @param raw 原始地址，可为 null、可带端口（含 [::1]:8080 形式）
     * @return 归一化后的地址；无法识别时返回 null，调用方不能把 null 当作内网
     */
    public static String normalize(String raw) {
        // 解析成字节：剥端口、判地址族、IPv4-mapped 降级都在 parse 里完成
        byte[] bytes = parse(raw);
        // 解析失败直接返回 null，不做任何猜测性兜底；成功才格式化
        return bytes == null ? null : format(bytes);
    }

    /**
     * 把 IP 解析成地址字节数组，供后续做位运算比较
     *
     * @param ip IP 字符串，可带端口、可为任意等价写法；允许为 null
     * @return 4 字节或 16 字节的地址；非法输入返回 null，调用方必须判空
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

    // CIDR

    /**
     * 把 192.168.1.0/24、2001:db8::/32 这类网段配置解析成可比较的对象
     *
     * @param cidrText 网段文本，形如 192.168.1.0/24、10.0.0.7（裸 IP 视为满位长）；允许为 null
     * @return 解析结果；地址非法、前缀越界时返回 null，调用方必须判空
     */
    public static Cidr parseCidr(String cidrText) {
        // 先挡 null 与空串：配置里写成空值是「没配」，不是「配错了」，两者提示不同
        if (cidrText == null) {
            return null;
        }
        String text = cidrText.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 用最后一个斜杠切分：IPv6 地址里含冒号但不含斜杠，前缀只可能出现在最后一段
        int slash = text.lastIndexOf('/');
        String ipPart = slash >= 0 ? text.substring(0, slash) : text;

        byte[] addr = parse(ipPart);
        // 地址都不合法，前缀再对也没有意义，直接返回
        if (addr == null) {
            return null;
        }

        // 满位长即「只有这一个地址」：裸 IP 写法等价于 /32 或 /128
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

        // 存的是清零主机位后的网络地址：匹配时才能直接逐字节比
        return new Cidr(text, maskBits(addr, prefixLength), prefixLength);
    }

    /**
     * 判断一个 IP 是否落在指定网段内
     *
     * @param ip 待判断的 IP，可带端口、可为 IPv4-mapped 形式；允许为 null
     * @param cidr 已解析的网段，可为 null（此时一律返回 false）
     * @return 命中返回 true；ip 非法、cidr 为 null 或地址族不同时返回 false
     */
    public static boolean matches(String ip, Cidr cidr) {
        // 网段为空说明配置没配好，一律不匹配：不能因为「没配置」就默认信任
        if (cidr == null) {
            return false;
        }
        byte[] addr = parse(ip);
        return addr != null && cidr.matches(addr);
    }

    /**
     * 判断 IP 是否落在任意一个网段内（配置里多个网段用逗号分隔，命中其一即算内网）
     *
     * @param ip IP
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

    // 解析实现

    /** 核心解析：剥离端口 → 判定地址族 → 解析字节 → IPv4-mapped 降级 */
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

        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return bytes;
    }

    /** 剥离端口后缀 */
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

    /** 解析 IPv4：严格要求四段、每段 0~255 的纯数字 */
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

    /** 解析 IPv6：支持 :: 压缩、尾部 IPv4 写法（ ::ffff:1.2.3.4）、以及 %zone 后缀（形如 fe80::1%eth0 的链路本地地址） */
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

    /** 解析单个十六进制组（1~4 个十六进制字符） */
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

    /** 是否为 IPv4-mapped IPv6（前 10 字节全 0，第 11、12 字节为 0xFF） */
    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    /** 把地址的主机位清零，得到网络地址 */
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

    /** 格式化为展示/存库字符串：IPv4 用点分十进制，IPv6 用 RFC 5952 压缩形式 */
    private static String format(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        return formatIpv6(bytes);
    }

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

    // 网段对象

    /**
     * 一个已经解析好的网段：网络地址 + 前缀长度
     *
     * @param source 原始配置文本，仅用于日志，便于定位是哪条配置命中的
     * @param network 网络地址字节（主机位已清零），长度 4 或 16
     * @param prefixLength 前缀长度，IPv4 为 0~32、IPv6 为 0~128
     */
    public record Cidr(String source, byte[] network, int prefixLength) {

        /**
         * 判断一个地址是否落在本网段内
         *
         * @param addr 待判断的地址字节（4 或 16 字节），可为 null
         * @return 命中返回 true；addr 为 null 或长度与网段不同时返回 false
         */
        public boolean matches(byte[] addr) {
            // 地址族必须相同：长度不同就不可能在同一网段，先挡掉避免越界访问
            if (addr == null || addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainBits = prefixLength % 8;
            // 整字节部分：任意一位不同就可以提前返回，不必比完全部
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remainBits > 0) {
                // 只剩不足一字节：两边都掩掉主机位再比，否则同网段也会被判不匹配
                int mask = (0xFF << (8 - remainBits)) & 0xFF;
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            // 前缀正好落在字节边界上，整字节部分全等即命中
            return true;
        }
    }
}

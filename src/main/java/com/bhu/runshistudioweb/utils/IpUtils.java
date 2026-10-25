package com.bhu.runshistudioweb.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 地址处理工具（纯静态、无 Spring 依赖、可直接单测）
 *
 * 只做三件事：归一化、解析 CIDR、判断某 IP 是否落在某网段内。
 * 业务判定（内外网、信任链）不在这里，见 manager/ClientIpManager。
 *
 * 为什么必须归一化，而不是拿到字符串直接比较——同一个客户端地址会有多种写法：
 * 同一地址的不同形态
 * 来源 | 实际取值
 * 直连（IPv4）| 192.168.1.5
 * 直连（带端口，某些容器/日志格式）| 192.168.1.5:54321
 * Nginx 监听双栈时转发的 IPv4 | ::ffff:192.168.1.5
 * IPv6 环回的完整写法 | 0:0:0:0:0:0:0:1 而非 ::1
 * 若不归一化，配置里写 192.168.1.0/24 将匹配不上 ::ffff:192.168.1.5，
 * 结果是「明明在工作室却记为外网」——而且完全不报错。
 *
 * 为什么不用 InetAddress.getByName(String) 来解析（本类最重要的纪律）：
 * 那个方法对非字面量输入会走 DNS 解析。攻击者只要传一个 X-Real-IP: abc.example.com
 * 就能让服务端发起一次 DNS 查询——既是性能问题（每次请求一次外部查询），
 * 也是 SSRF 面（可以让服务器去解析任意域名）。因此本类完全自己解析，
 * 只在最后用 InetAddress.getByAddress(byte[]) 格式化输出（该重载不做任何网络查询）。
 *
 * IPv4-mapped IPv6 的归一化规则：::ffff:a.b.c.d 这类地址在语义上
 * 就是 IPv4 地址（RFC 4291 的 IPv4-mapped 形式），因此本类把它降级成 4 字节的 IPv4。
 * 这是让「IPv6 监听下的 IPv4 客户端」也能正确匹配 IPv4 网段的关键一步。
 * 注意与之形近的 ::a.b.c.d（IPv4-compatible 形式）不做转换——
 * 它没有 ffff 标记，混进来会造成误判。
 *
 * @author shaoshing
 */
public final class IpUtils {

    private IpUtils() {
    }

    // ==================== 归一化 ====================

    /**
     * 把任意写法的 IP 归一化成唯一的标准写法
     *
     * <p>为什么这样设计：同一个客户端地址会有多种写法——直连是 192.168.1.5，
     * 带端口是 192.168.1.5:54321，Nginx 双栈监听转发过来是 ::ffff:192.168.1.5，
     * IPv6 环回可能是完整写法 0:0:0:0:0:0:0:1 而不是 ::1。
     * 如果直接拿原始字符串去和配置里的网段比较，配置写 192.168.1.0/24
     * 就匹配不上 ::ffff:192.168.1.5，结果是「人明明在工作室，却记为外网」，
     * 而且完全不报错——这类静默误判比抛异常难查得多。归一化就是为了
     * 让「同一个地址」在任何来源下都得到同一个字符串。
     *
     * <p>为什么无法识别时返回 null 而不是抛异常：本方法的入参直接来自
     * 请求头（X-Real-IP、X-Forwarded-For），是完全由客户端控制的数据。
     * 一个恶意请求就能传一段乱码，若这里抛异常，攻击者可以靠构造请求头
     * 让接口 500。返回 null 则把「认不出来」变成一个普通的数据状态，
     * 调用方按「不可信」处理即可。也正因如此，调用方绝不能把 null 当成内网。
     *
     * <p>为什么把 IPv4-mapped 的 IPv6 降级成 IPv4：::ffff:192.168.1.5 这类地址
     * 在语义上就是 IPv4 地址（RFC 4291 的 IPv4-mapped 形式），只是被双栈监听
     * 的 Nginx 用 IPv6 的壳包了一层。如果不降级，它就是一个 16 字节地址，
     * 和配置里任何 IPv4 网段（4 字节）都比不上——判断网段时地址族不同直接不匹配，
     * 结果是「所有经 IPv6 监听的请求都被判成外网」，而且没有任何报错。
     * 注意与之形近的 ::192.168.1.5（IPv4-compatible，没有 ffff 标记）刻意不转换：
     * 它不是一个「被包装的 IPv4」，混进来会造成误判。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>交给 parse 完成全部解析：剥端口、判定地址族、按族解析字节、
     *     把 IPv4-mapped 降级成 4 字节；</li>
     *     <li>解析成功才格式化：IPv4 输出点分十进制，IPv6 输出 RFC 5952 压缩形式。
     *     解析失败直接返回 null，不做任何猜测性的兜底。</li>
     * </ol>
     *
     * <p>容易踩的坑：不要以为归一化只是「去空格 + 小写」这种文本处理。
     * 地址等价性有严格的协议定义（::1 与 0:0:0:0:0:0:0:1 是同一个地址），
     * 用字符串比较来判断等价一定会漏。另一个常见误区是把 IPv4-compatible
     * （::192.168.1.5，没有 ffff）也当成 IPv4 降级——那会误判，
     * 本类刻意只认 IPv4-mapped。
     *
     * @param raw 原始地址，可为 null、可带端口（含 [::1]:8080 这种方括号形式）、
     *            可为任意等价的 IPv6 写法；传 null 或空串时返回 null
     * @return IPv4 的点分十进制或 IPv6 的压缩形式；无法识别时返回 null，
     * 调用方必须判空，不能把 null 当作「内网」或「本机」
     */
    public static String normalize(String raw) {
        // ① 解析成字节：剥端口、判地址族、IPv4-mapped 降级都在 parse 里完成
        byte[] bytes = parse(raw);
        // ② 解析失败直接返回 null，不做任何猜测性兜底；成功才格式化
        return bytes == null ? null : format(bytes);
    }

    /**
     * 把 IP 解析成地址字节数组，供后续做位运算比较
     *
     * <p>为什么这样设计：判断一个地址是否落在网段里，本质上是「按掩码逐位比较」，
     * 这是位运算而不是字符串比较。字符串没有位，必须先转成字节数组才能做
     * 与、移位、按位比较；而且字节数组天然区分地址族（4 字节是 IPv4、
     * 16 字节是 IPv6），长度不同就说明两者根本不可能在同一网段。
     *
     * <p>为什么对外暴露这个方法而不是让调用方自己 parse：调用方（例如
     * ClientIpManager 做网段匹配、日志归因）需要拿字节直接和多个网段比，
     * 每比一次就归一化成字符串再解析一遍纯属浪费；而且暴露字节入口
     * 能保证「解析规则只有一份」，不会有人另写一套正则去切 IP。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>调用内部的 parse 完成剥离端口、判定地址族、逐段解析；</li>
     *     <li>原样返回字节数组，成功时长度只会是 4 或 16；失败时返回 null。</li>
     * </ol>
     *
     * <p>容易踩的坑：返回的数组是新建的，调用方修改它不会影响任何共享状态；
     * 但也正因为每次都是新数组，不要把它放进高频循环里反复创建。
     * 另外绝不能拿返回值直接 new String(bytes)——那得到的是二进制乱码，
     * 要展示请用 {@link #normalize(String)}。
     *
     * @param ip IP 字符串，可带端口、可为任意等价写法；允许为 null
     * @return 4 字节（IPv4）或 16 字节（IPv6）的地址；非法输入返回 null，
     * 调用方必须判空后再做位运算，否则会 NPE
     */
    public static byte[] toBytes(String ip) {
        // ① 直接转发给 parse：解析规则只有一份，调用方不必另写正则去切 IP
        // ② 成功时长度只会是 4 或 16；失败返回 null，调用方必须判空
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
     * 把 192.168.1.0/24、2001:db8::/32 这类网段配置解析成可比较的对象
     *
     * <p>为什么这样设计：解析一次、多次匹配。配置在启动时读入后会被反复使用
     * （每个请求都要判断来源 IP 是否落在可信代理 / 内网网段里），
     * 如果每次匹配都重新切字符串、算掩码，等于把同样的工作重复几万次。
     * 解析结果里存的是「主机位已清零的网络地址字节」与「前缀长度」，
     * 匹配时只需逐字节比，不再碰字符串。
     *
     * <p>为什么容错接受 192.168.1.5/24 这种「网络地址不规整」的写法：
     * 配置者往往直接复制本机 IP 再补一个掩码，这是最常见的真实写法。
     * 拒绝它只会换来一次「配置看着没问题却不生效」的排查——那种排查极费时间，
     * 因为从配置文本上完全看不出错在哪。因此内部统一把主机位清零，
     * 得到 192.168.1.0/24 再使用。注意这不是纵容写错，而是把「可自动纠正的偏差」
     * 消化掉；真正无法解释的输入（前缀越界、地址非法）仍然一律返回 null。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先挡 null 与空串，再 trim——配置里带首尾空格很常见；</li>
     *     <li>用最后一个斜杠切出地址部分与前缀部分。之所以取 lastIndexOf
     *     而不是 indexOf，是因为 IPv6 地址里含冒号但不含斜杠，
     *     而前缀只可能出现在最后一段，取最后一个斜杠最稳；</li>
     *     <li>解析地址部分，失败立刻返回 null——地址都不合法，前缀再对也没有意义；</li>
     *     <li>解析前缀：缺省时按地址位长（IPv4 为 32、IPv6 为 128）处理，
     *     相当于一条裸 IP 代表「只有它自己」的网段；</li>
     *     <li>校验前缀范围并清零主机位，组装成 Cidr 对象。</li>
     * </ol>
     *
     * <p>容易踩的坑：前缀长度越界必须在这里拒绝，不能等到匹配时再说。
     * /33（IPv4）或 /129（IPv6）会让后面的掩码计算出现负数移位，
     * 而在 Java 里移位位数是按 32 取模的——负数移位不会抛异常，
     * 只会算出一个完全错误的掩码，于是这条配置静默地匹配上不该匹配的地址。
     * 另外 /24.5、/abc 这类非整数前缀也不做兜底猜测，直接判非法。
     *
     * @param cidrText 网段文本，形如 192.168.1.0/24、10.0.0.7（裸 IP 视为满位长）、
     *                 2001:db8::/32；允许为 null
     * @return 解析结果；入参为 null、空串、地址非法或前缀越界时返回 null，
     * 调用方必须判空（配置写错时应当启动即失败或记警告，不能静默当成「没有网段」）
     */
    public static Cidr parseCidr(String cidrText) {
        // ① 先挡 null 与空串：配置里写成空值是「没配」，不是「配错了」，两者提示不同
        if (cidrText == null) {
            return null;
        }
        String text = cidrText.trim();
        if (text.isEmpty()) {
            return null;
        }

        // ② 用最后一个斜杠切分：IPv6 地址里含冒号但不含斜杠，前缀只可能出现在最后一段
        int slash = text.lastIndexOf('/');
        String ipPart = slash >= 0 ? text.substring(0, slash) : text;

        byte[] addr = parse(ipPart);
        // ③ 地址都不合法，前缀再对也没有意义，直接返回
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
            // ④ 前缀长度越界必须拒绝：/33（IPv4）或 /129（IPv6）会让掩码计算产生负数移位
            if (prefixLength < 0 || prefixLength > maxBits) {
                return null;
            }
        }

        // ⑤ 存的是清零主机位后的网络地址：匹配时才能直接逐字节比
        return new Cidr(text, maskBits(addr, prefixLength), prefixLength);
    }

    /**
     * 判断一个 IP 是否落在指定网段内
     *
     * <p>为什么这样设计：入参 ip 是原始字符串，本方法内部先归一化再匹配。
     * 这样调用方不需要记得「先 normalize 再 matches」，少一步就少一个出错的机会；
     * 代价是每次匹配都要解析一遍字符串，因此高频场景应当用
     * {@link #toBytes(String)} 先取字节、再直接调 {@link Cidr#matches(byte[])}。
     *
     * <p>为什么各种异常情况一律返回 false 而不是抛异常：这个方法用在请求链路上，
     * 入参来自请求头，完全由客户端控制。攻击者只要传一个非法 IP 就能让接口 500，
     * 若这里抛异常，等于把「IP 不可信」升级成了「服务不可用」。
     * 返回 false 的语义也很明确：认不出、或不在网段内，都不该享受网段带来的信任。
     *
     * <p>执行步骤：
     * <ol>
     *     <li>先判网段是否为 null——配置没配好时这里会是 null，直接返回 false；</li>
     *     <li>解析 IP，失败返回 false；</li>
     *     <li>交给 Cidr.matches 做地址族检查与逐字节比较。</li>
     * </ol>
     *
     * <p>容易踩的坑：false 同时表示「不在网段内」和「地址根本认不出来」，
     * 两者在安全上的处置相同（都不信任），但排查时含义完全不同。
     * 若需要区分，调用方应自己先调 {@link #isValid(String)} 或
     * {@link #normalize(String)} 判断，而不是指望本方法返回更多信息。
     *
     * @param ip 待判断的 IP，可带端口、可为 IPv4-mapped 形式；允许为 null
     * @param cidr 已解析的网段，可为 null（此时一律返回 false）
     * @return 命中返回 true；ip 非法、cidr 为 null、或两者地址族不同
     * （IPv4 与 IPv6）时返回 false
     */
    public static boolean matches(String ip, Cidr cidr) {
        // ① 网段为空说明配置没配好，一律不匹配：不能因为「没配置」就默认信任
        if (cidr == null) {
            return false;
        }
        byte[] addr = parse(ip);
        // ② 解析失败返回 false 而不是抛异常：入参来自请求头，完全由客户端控制
        // ③ 地址族检查与逐字节比较都在 Cidr.matches 里完成
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
     * 三种形态要分开处理，不能简单按冒号切：
     * - [::1]:8080 → 方括号形式，取括号内；
     * - 192.168.1.5:8080 → 恰好一个冒号且冒号前含点，判定为 IPv4 带端口；
     * - ::1 / 2001:db8::1 → 冒号多于一个，是 IPv6 本体，不能切
     * （切了就只剩 :: 或 2001 这类错误地址）。
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
     * 刻意不接受 0x10.1.1.1、010.1.1.1 这类历史写法
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
     * 解析 IPv6：支持 :: 压缩、尾部 IPv4 写法（ ::ffff:1.2.3.4）、
     * 以及 %zone 后缀（形如 fe80::1%eth0 的链路本地地址）
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
     * @param addr 原地址字节
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
     * 为什么自己做 IPv6 压缩而不调 InetAddress.getHostAddress()：
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
     * IPv6 压缩格式化：把最长的一段连续零组（长度 ≥ 2）替换为 ::
     *
     * 只压缩最长的一段，而不是见零就压：2001:0:0:1:0:0:0:1 若压成
     * 2001::1:0:0:0:1 会增加歧义（RFC 5952 明确要求只压最长段）。
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
        // 因此不能靠「先 append 单冒号、最后裁掉尾部冒号」来实现。
        // 若那样写，零段在开头（::1 → ":1"）或末尾（2001:db8:: → "2001:db8:"）
        // 都会少一个冒号，产出的是一个无法再被本类解析的非法串。
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
     * 一个已经解析好的网段：网络地址 + 前缀长度
     *
     * <p>为什么这样设计：用 record 而不是普通类，是因为它是纯粹的数据载体，
     * 字段在构造后不再变化，也不需要 getter 之外的任何行为。record 自动生成
     * 构造器、访问器、equals / hashCode / toString，正好省掉几十行样板代码；
     * 而且它是不可变的（字段隐含 final），可以安全地在多线程间共享——
     * 本对象正是在启动时解析一次、之后被所有请求线程反复读取。
     *
     * <p>为什么单独存 source：它只用于日志与报错，让排查时能直接说出
     * 「命中的是配置里哪一条」，而不是只给出一串字节。没有它，
     * 多网段配置下「到底哪条规则生效了」只能靠人肉比对。
     *
     * <p>为什么 network 存的是清零主机位后的地址：匹配时只需要逐字节比较，
     * 不必每次重新计算掩码。这一步在构造时就做完（见 maskBits），
     * 把开销从「每次匹配」挪到「每次解析」，而解析只发生一次。
     *
     * <p>容易踩的坑：byte[] 是可变对象，record 的不可变只保证「引用不变」，
     * 不保证数组内容不被改。本类所有构造路径传进来的都是新建数组，
     * 且对外没有暴露修改手段，因此实际是安全的；但如果将来给 Cidr 加上
     * 返回 network 的方法，就必须返回副本，否则调用方一改就会污染这条共享配置。
     *
     * <p>执行步骤（构造过程本身只有一行，真正的工作在两步里完成）：
     * <ol>
     *     <li>由 parseCidr 解析地址并校验前缀长度，保证传进来的字节与位数都合法；</li>
     *     <li>由 maskBits 把主机位清零得到网络地址，让后续每次匹配都只需逐字节比较，
     *     不必重复计算掩码。</li>
     * </ol>
     *
     * @param source 原始配置文本，仅用于日志，便于定位是哪条配置命中的
     * @param network 网络地址字节（主机位已清零），长度 4（IPv4）或 16（IPv6）
     * @param prefixLength 前缀长度，IPv4 为 0~32、IPv6 为 0~128
     */
    public record Cidr(String source, byte[] network, int prefixLength) {

        /**
         * 判断一个地址是否落在本网段内
         *
         * <p>为什么这样设计：网段匹配的本质是「前 prefixLength 位是否相同」。
         * 转成字节数组后可以按字节批量比：整字节部分直接 {@code !=} 短路，
         * 只有最后一个不完整的字节才需要按位与掩码。这比把地址转成
         * 32 位或 128 位整数再移位要稳——IPv6 是 128 位，Java 没有对应的原生整型，
         * 硬凑成两个 long 反而容易在边界上写错。
         *
         * <p>为什么地址族不同时直接返回 false，而不是把 IPv4 展开成
         * ::ffff: 形式再比：那会让一条 IPv6 网段意外匹配上大量 IPv4 地址，
         * 例如 ::/0 会把所有 IPv4 都算进去。地址族不同就是不可能在同一网段，
         * 这个判断必须在比较第一位之前完成。
         *
         * <p>执行步骤：
         * <ol>
         *     <li>先比长度：长度不同说明地址族不同，立刻返回 false；</li>
         *     <li>比完整的字节部分（prefixLength / 8 个），任一字节不同即返回 false；</li>
         *     <li>若还有不足一字节的余数位，把两边都与同一个掩码相与后再比——
         *     必须先掩码再比较，直接比原字节会把主机位也纳入比较，导致同网段的地址被判为不匹配。</li>
         * </ol>
         *
         * <p>容易踩的坑：掩码要用 {@code (0xFF << (8 - remainBits)) & 0xFF} 算。
         * 少了最后的 {@code & 0xFF}，int 提升后高位全是 1，与 byte 相与时
         * 符号扩展会带来一堆意外的 1，边界网段就会误判。另外这里是逐位比较，
         * 不要图省事改成「把地址转成字符串再 startsWith 前缀」——
         * 192.168.1.5 与 192.168.1.50 的前缀相同，但后者不在 /24 里。
         *
         * @param addr 待判断的地址字节（4 或 16 字节），可为 null
         * @return 命中返回 true；addr 为 null 或长度与网段不同时返回 false
         */
        public boolean matches(byte[] addr) {
            // ① 地址族必须相同：长度不同就不可能在同一网段，先挡掉避免越界访问
            if (addr == null || addr.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainBits = prefixLength % 8;
            // ② 整字节部分：任意一位不同就可以提前返回，不必比完全部
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            if (remainBits > 0) {
                // ③ 只剩不足一字节：两边都掩掉主机位再比，否则同网段也会被判不匹配
                int mask = (0xFF << (8 - remainBits)) & 0xFF;
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            // 前缀正好落在字节边界上，整字节部分全等即命中
            return true;
        }
    }
}

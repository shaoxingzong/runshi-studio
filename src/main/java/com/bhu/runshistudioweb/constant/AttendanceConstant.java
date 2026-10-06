package com.bhu.runshistudioweb.constant;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 考勤模块公共常量
 *
 * author: shaoshing
 *
 * <p><b>为什么时区要写成一个显式常量，而不是靠 {@code LocalDateTime.now()} 的默认时区</b>：
 * 服务器的默认时区取决于部署环境（Linux 容器默认多半是 UTC，而 Windows 开发机是东八区），
 * 一旦遵循环境默认值，同一份代码在本地与生产会落库出<b>相差 8 小时</b>的签到时间，
 * 而且是「无声的错」——接口不报错、数据看着也像真的，只有做日报表按天归类时才会暴露。
 * 因此签到时间与 {@code attendance_date} 的派生口径，全部固定取本常量。
 *
 * <p>注意：本常量必须与数据库连接串上的 {@code serverTimezone=Asia/Shanghai} 保持同一口径，
 * 否则 JDBC 写入 {@code datetime} 时还会再做一次时区换算。
 */
public final class AttendanceConstant {

    /**
     * 考勤业务时区：固定东八区（中国标准时间）
     *
     * <p>不用 {@code ZoneId.systemDefault()}：那等于把「哪一天算今天」交给部署环境决定。
     */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 日期格式：{@code yyyy-MM-dd}
     *
     * <p>仅用于日志与文档示例，不用于数据库写入——{@code attendance_date} 是 {@code date} 类型，
     * 由 {@code LocalDate} 直接映射，中间不该经过字符串。
     */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * {@code user_agent} 列的最大长度：512
     *
     * <p><b>必须与 DDL 中 {@code user_agent varchar(512)} 严格一致</b>，且这里写成常量而非配置项——
     * 一旦做成可配置，运维调大到 1024 就会让 MySQL 在非严格模式下<b>静默截断</b>，
     * 或严格模式下直接报错。列宽是数据库契约，不该由运行期配置决定。
     *
     * <p>为什么入库前要主动截断：UA 是客户端完全可控的字符串，脚本可以传几 MB 的 UA，
     * 不截断就是一条稳定的报错来源（长数据入库失败 → 签到失败）。
     */
    public static final int USER_AGENT_MAX_LENGTH = 512;

    private AttendanceConstant() {
    }
}

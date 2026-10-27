package com.bhu.runshistudioweb.constant;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 考勤模块公共常量
 *
 * 时区必须写成显式常量，不能靠 LocalDateTime.now() 的默认时区：那取决于部署环境
 * （Linux 容器默认多半是 UTC，Windows 开发机是东八区），同一份代码在本地与生产会落库出
 * 相差 8 小时的签到时间，而且是「无声的错」——接口不报错、数据看着也像真的，
 * 只有按天归类做日报表时才会暴露。
 *
 * 本常量必须与数据库连接串上的 serverTimezone=Asia/Shanghai 保持同一口径，
 * 否则 JDBC 写入 datetime 时还会再做一次时区换算。
 *
 * @author shaoshing
 */
public final class AttendanceConstant {

    /**
     * 考勤业务时区：固定东八区（中国标准时间）
     *
     * 不用 ZoneId.systemDefault()：那等于把「哪一天算今天」交给部署环境决定。
     */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 日期格式：yyyy-MM-dd
     *
     * 仅用于日志与文档示例，不用于数据库写入——attendance_date 是 date 类型，
     * 由 LocalDate 直接映射，中间不该经过字符串。
     */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * user_agent 列的最大长度：512
     *
     * 必须与 DDL 中 user_agent varchar(512) 严格一致，且写成常量而非配置项：一旦可配置，
     * 运维调大到 1024 就会让 MySQL 在非严格模式下静默截断、严格模式下直接报错。
     * 列宽是数据库契约，不该由运行期配置决定。
     *
     * 入库前要主动截断：UA 是客户端完全可控的字符串，脚本可以传几 MB，
     * 不截断就是一条稳定的报错来源（长数据入库失败 → 签到失败）。
     */
    public static final int USER_AGENT_MAX_LENGTH = 512;

    private AttendanceConstant() {
    }
}

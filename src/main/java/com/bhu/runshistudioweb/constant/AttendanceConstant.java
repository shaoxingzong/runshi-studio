package com.bhu.runshistudioweb.constant;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 考勤模块公共常量
 *
 * @author shaoshing
 */
public final class AttendanceConstant {

    /** 考勤业务时区：固定东八区（中国标准时间） */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 日期格式：yyyy-MM-dd */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** user_agent 列的最大长度：512 */
    public static final int USER_AGENT_MAX_LENGTH = 512;

    private AttendanceConstant() {
    }
}

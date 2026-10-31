package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端：成员某月签到汇总（一行 = 一位成员）
 *
 * @author shaoshing
 */
@Data
public class AttendanceMonthStatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员绑定的登录账号 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String userName;

    /** 成员状态：0-在读/在队，1-毕业/离队（历史月份可能出现在队、现已离队的成员） */
    private Integer memberStatus;

    /** 当月签到天数：按 attendance_date 去重 */
    private Integer days;

    /** 当月签到次数：原始记录条数，同一天多次会大于 days */
    private Integer times;

    /** 当月首次签到时刻 */
    private LocalDateTime firstAt;

    /** 当月最后一次签到时刻 */
    private LocalDateTime lastAt;
}

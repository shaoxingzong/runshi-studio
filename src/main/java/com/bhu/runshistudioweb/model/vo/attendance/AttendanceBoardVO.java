package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 今日出勤看板视图对象（仅工作室成员可见）
 *
 * @author shaoshing
 */
@Data
public class AttendanceBoardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员绑定的登录账号 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 展示名：来自 studio_member.name */
    private String userName;

    /** 今日打卡时刻（东八区），精确到秒 */
    private LocalDateTime checkInAt;

    /** 今日是否已打卡 */
    private Boolean checkedIn;

    /** 下次可签到时刻（东八区） */
    private LocalDateTime nextCheckInAt;

    /** 本月打卡次数（自然月内的签到记录条数） */
    private Integer monthCount;
}

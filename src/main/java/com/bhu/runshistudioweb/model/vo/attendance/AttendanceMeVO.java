package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本人签到历史视图对象（只能查自己的）
 *
 * @author shaoshing
 */
@Data
public class AttendanceMeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 签到用户 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 签到日期（冗余列，与 checkInAt 同源派生，便于前端按天分组） */
    private LocalDate attendanceDate;

    /** 签到时刻（东八区），精确到秒 */
    private LocalDateTime checkInAt;
}

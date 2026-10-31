package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 签到接口的返回值
 *
 * @author shaoshing
 */
@Data
public class AttendanceCheckInVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 签到用户 ID（雪花 19 位，序列化为字符串） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private LocalDateTime checkInAt;

    private Integer monthCount;
}

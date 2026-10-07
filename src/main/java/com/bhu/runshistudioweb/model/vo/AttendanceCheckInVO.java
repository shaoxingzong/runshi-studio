package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 签到接口的返回值
 *
 * author: shaoshing
 *
 * <p>签到成功后立刻把「这次签到的结论」回给前端：几点签的、算不算内网、本月累计几次。
 * 这样前端一次请求就能刷新页面，不必再回头调一次看板接口。
 *
 * <p>同样守 {@link AttendanceBoardVO} 的脱敏红线：<b>不返回 ip / userAgent / userAccount</b>，
 * 网络信息只以 {@code inLan} 布尔的形式出现。
 */
@Data
public class AttendanceCheckInVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 签到用户 ID（雪花 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 本次签到时刻（东八区），精确到秒
     */
    private LocalDateTime checkInAt;

    /**
     * 本次是否判为内网签到
     */
    private Boolean inLan;

    /**
     * 本月累计签到次数（含本次）
     */
    private Integer monthCount;
}

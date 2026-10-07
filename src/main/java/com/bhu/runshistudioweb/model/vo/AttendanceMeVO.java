package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 本人签到历史视图对象（只能查自己的）
 *
 * author: shaoshing
 *
 * <p>与 {@link AttendanceBoardVO} 的区别：看板是<b>成员之间的横向对比</b>，
 * 本 VO 是<b>单个成员自己的纵向记录</b>，因此带上 {@code attendanceDate} 便于按天展示。
 *
 * <p>脱敏规则与看板完全一致：<b>不含 ip / userAgent / userAccount</b>。
 * 即便是查自己的记录也照样脱敏——因为这个接口的返回值同样会被前端缓存、
 * 被日志中间件采样打印，多一个敏感字段就多一处泄露面。
 */
@Data
public class AttendanceMeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 签到用户 ID（雪花 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 签到日期（冗余列，与 checkInAt 同源派生，便于前端按天分组）
     */
    private LocalDate attendanceDate;

    /**
     * 签到时刻（东八区），精确到秒
     */
    private LocalDateTime checkInAt;

    /**
     * 是否内网签到（true-工作室网段）
     */
    private Boolean inLan;
}

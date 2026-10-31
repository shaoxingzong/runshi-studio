package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端：某成员某月的原始签到记录（一行 = 一次签到）
 *
 * @author shaoshing
 */
@Data
public class AttendanceRecordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 签到时刻（东八区），精确到秒 */
    private LocalDateTime checkInAt;

    /** 签到时记录的客户端 IP（反向代理场景下为可信代理透传的真实地址） */
    private String ip;
}

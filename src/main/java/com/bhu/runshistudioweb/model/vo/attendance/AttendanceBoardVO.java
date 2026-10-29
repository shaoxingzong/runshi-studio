package com.bhu.runshistudioweb.model.vo.attendance;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 今日出勤看板视图对象（仅工作室成员可见）
 *
 * 看板回答三个问题：谁打卡了（userName）、几点打卡（checkInAt）、这个月来了几次（monthCount）。
 * 未打卡的成员同样出现在列表里，用 checkedIn=false + checkInAt=null 表示。
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

    /**
     * 今日打卡时刻（东八区），精确到秒
     *
     * 同一天可能有多条签到记录，这里取当天最早的一次——语义上回答的是「几点到的」。
     * 未打卡时为 null；checkedIn 就是由它是否为 null 决定的冗余标记，单独给一个字段是为了让前端不必自己判空。
     */
    private LocalDateTime checkInAt;

    /** 今日是否已打卡 */
    private Boolean checkedIn;

    /**
     * 下次可签到时刻（东八区）
     *
     * 由「当天最后一次签到时刻 + 配置的最小间隔」算出，为 null 表示当前不受限（未签到或不启用防抖）。
     * 为什么由后端算而不是前端拿 checkInAt + 固定 60 秒：间隔是配置项，前端写死会在运维改了
     * studio.attendance.min-interval-seconds 之后与后端判断不一致，表现为「按钮能点但必然报错」。
     *
     * 注意与 checkInAt 的口径差异：checkInAt 是当天**最早**一次（用于展示「几点到的」），
     * 本字段要基于**最晚**一次（防抖只看最近一次），两者不能混用。
     */
    private LocalDateTime nextCheckInAt;

    /**
     * 本月打卡次数（自然月内的签到记录条数）
     *
     * 注意语义：同一天多次签到会分别计数。这与考勤惯例略有出入，但签到记录就是「到场凭证」，
     * 保留原始条数比在展示层去重更诚实（去重会掩盖重复打卡的异常）。
     */
    private Integer monthCount;

    /**
     * 今日是否内网签到（true-命中工作室网段，false-外网）
     *
     * 只给布尔结论，不给具体 IP 或网段名：它回答的是「人在不在工作室」，
     * 从哪个地址连进来属于位置信息，留在数据库里做审计即可。
     */
    private Boolean inLan;
}

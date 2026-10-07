package com.bhu.runshistudioweb.model.vo;

import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 今日出勤看板视图对象（**仅工作室成员可见**）
 *
 * author: shaoshing
 *
 * <p>看板回答三个问题：<b>谁打卡了</b>（{@code userName}）、<b>几点打卡</b>（{@code checkInAt}）、
 * <b>这个月来了几次</b>（{@code monthCount}）。未打卡的成员同样出现在列表里，
 * 用 {@code checkedIn=false} + {@code checkInAt=null} 表示，方便前端区分「今天还没来」。
 *
 * <p><b>隐私红线（本 VO 存在的理由）</b>：它<b>没有、也不可以有</b>
 * {@code ip} / {@code userAgent} / {@code userAccount} 这三个字段。
 * 脱敏靠的是<b>目标 VO 的字段结构</b>——用 {@code BeanUtils.copyProperties} 从实体拷贝时，
 * 实体里那些敏感的审计字段根本无处可去。这比「拷完再手动置空」可靠：
 * 将来给 {@code studio_attendance} 加新的敏感列时，不会被漏掉。
 *
 * <p>展示名取 {@code studio_member.name}（管理员后台录入的成员姓名），
 * 不取 {@code sys_user.user_account}——后者是登录凭据的一部分，
 * 一旦出现在可被其他成员看到的列表里就成了信息泄露。
 *
 * <p>{@code userId} 上的 {@link JsonSerialize} 与 {@link LoginUserVO} 同理：
 * 全局 {@code JsonConfig} 已把 Long 统一转字符串，这里再标一次是<b>字段级兜底</b>，
 * 保证即使哪天全局规则被调整，用户 ID 也不会退化成数字而被前端截断精度。
 */
@Data
public class AttendanceBoardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成员绑定的登录账号 ID（雪花 19 位，序列化为字符串）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 展示名：来自 studio_member.name
     */
    private String userName;

    /**
     * 今日打卡时刻（东八区），精确到秒
     *
     * <p>同一天可能有多条签到记录（每隔一段时间才能再签一次），
     * 这里取的是<b>当天最早的一次</b>——语义上回答的是「几点到的」。
     *
     * <p>未打卡时为 {@code null}；{@code checkedIn} 就是由它是否为 null 决定的冗余标记，
     * 单独给一个字段是为了让前端不必自己判空。
     */
    private LocalDateTime checkInAt;

    /**
     * 今日是否已打卡
     */
    private Boolean checkedIn;

    /**
     * 本月打卡次数（自然月内的签到记录条数）
     *
     * <p>注意语义：统计的是<b>自然月内的签到次数</b>，同一天多次签到会分别计数。
     * 这与考勤惯例略有出入，但本项目的签到记录就是「到场凭证」，
     * 保留原始条数比在展示层去重更诚实（去重会掩盖重复打卡的异常）。
     */
    private Integer monthCount;

    /**
     * 今日是否内网签到（true-命中工作室网段，false-外网）
     *
     * <p>只给布尔结论，不给具体 IP 或网段名——它回答的是「人在不在工作室」，
     * 至于从哪个地址连进来属于位置信息，留在数据库里做审计即可。
     */
    private Boolean inLan;
}

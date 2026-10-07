package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 考勤签到记录实体（对应 studio_attendance）
 *
 * author: shaoshing
 *
 * <p><b>本表最重要的性质：它是一条审计凭证，没有「修改」这个动作。</b>
 * 应用层不提供任何更新或删除接口（不写 {@code update} / {@code remove} 方法），
 * 因此每一行的 {@code updated_at} 恒等于 {@code created_at}、{@code updated_by} 恒为初始值。
 * 这两个字段仍然按全局约定补齐，目的是十张表结构统一，
 * 也给 DBA 在极端误操作时留出手工修正的余地——但它们不应该被业务代码写入。
 *
 * <p><b>{@code attendance_date} 是冗余列，且这个冗余是刻意的</b>：
 * 它由 {@code check_in_at} 按 {@code Asia/Shanghai} 派生后单独存一列。
 * 目的是让「按天查询」能走索引 {@code idx_date_lan_time}；
 * 若写成 {@code WHERE DATE(check_in_at) = ?}，函数会让索引彻底失效，
 * 全表扫描在考勤这种持续增长的表上是会随时间恶化的。
 * 冗余的代价是「两列可能不一致」，所以派生逻辑集中在
 * {@code AttendanceServiceImpl} 一处完成，不允许别处各自算。
 *
 * <p><b>{@code ip} 与 {@code userAgent} 是审计字段，绝不出现在任何对外 VO 里</b>：
 * 它们是「某人在某刻从哪个网络签到」的位置信息，
 * 一旦随列表返回给其他成员，就变成了隐私泄露。
 * 成员之间的出勤看板只返回由此派生出的 {@code in_lan} 布尔标记。
 *
 * <p>时间相关规定见 {@link com.bhu.runshistudioweb.constant.AttendanceConstant}：
 * 签到时刻与 {@code attendance_date} 的派生口径都固定取东八区，
 * 不用 {@code ZoneId.systemDefault()}，否则本地开发与生产部署会差 8 小时且不报错。
 */
@Data
@TableName("studio_attendance")
public class StudioAttendance implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 签到用户 ID（关联 sys_user.id）
     *
     * <p>只有绑定了账号的<b>在队工作室成员</b>会产生记录——
     * 「是否是成员」由 {@code studio_member} 表判定（见 {@code AttendanceServiceImpl}），
     * 因此这里存的必然是已通过成员校验的用户。
     */
    private Long userId;

    /**
     * 签到日期（Asia/Shanghai）
     *
     * <p>由 {@code checkInAt} 派生的冗余列，唯一用途是让按天查询命中索引。
     * 注意它是 {@code date} 类型，Java 侧用 {@link LocalDate}，
     * 中间不要经过字符串，避免时区与格式的隐式转换。
     */
    private LocalDate attendanceDate;

    /**
     * 签到时刻（Asia/Shanghai 本地时间）
     *
     * <p>DDL 是 {@code datetime}（MySQL 默认精度 0，即存到秒），
     * 看板展示到分钟即可，秒级精度留作排查用。
     */
    private LocalDateTime checkInAt;

    /**
     * 是否内网签到：0-外网，1-内网
     *
     * <p>由 {@code ClientIpManager} 按配置的局域网网段判定。
     * 用 {@code Integer} 而不是 {@code Boolean}：DDL 是 {@code tinyint}，
     * 而这一列的语义将来可能扩展（例如区分「工作室 WiFi」与「实验室网段」）。
     */
    private Integer inLan;

    /**
     * 来源 IP（归一化后；审计用，绝不对外）
     *
     * <p>存的是 {@code IpUtils.normalize} 的结果：同一地址的各种写法
     * （带端口、IPv4-mapped、IPv6 完整写法）都会归一成同一种字符串，
     * 否则事后排查时「同一个人的记录显示出两种地址」会严重误导。
     *
     * <p>取不到真实 IP（非常规传输层）时存占位串，而不是让签到失败——
     * 考勤记录本身比 IP 的准确性更重要，且缺失是极少数情况。
     */
    private String ip;

    /**
     * 客户端 UA（截断到 {@code AttendanceConstant.USER_AGENT_MAX_LENGTH}；审计用，绝不对外）
     *
     * <p>它是客户端完全可控的字符串，不截断就是一条稳定的报错来源
     * （脚本传几 MB 的 UA → 入库失败 → 签到失败）。
     */
    private String userAgent;

    /**
     * 创建人 ID：插入时由 MyMetaObjectHandler 填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 更新人 ID：插入与更新时都会填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     *
     * <p>本表无更新路径，它实际恒等于 {@code createdAt}。
     * 字段保留只为与其余九张表结构统一。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     *
     * <p>应用层不提供删除，这个字段只为「极端误操作由 DBA 手工处理」保留；
     * 加 {@code @TableLogic} 后，MP 的查询会自动追加 {@code deleted_at = 0}，
     * 因此被 DBA 标记过的记录不会再被统计进出勤看板。
     */
    @TableLogic
    private Long deletedAt;
}

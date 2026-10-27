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
 * 审计凭证，没有修改动作：updated_at 恒等于 created_at，两列只为结构统一保留。
 * attendance_date 是刻意冗余：由 check_in_at 按东八区派生，让按天查询命中索引；
 * 写成 DATE(check_in_at) 会让索引失效，派生逻辑只在 AttendanceServiceImpl 一处。
 *
 * 坑：ip 与 userAgent 是位置信息，绝不进对外 VO；时间口径固定东八区，
 * 不用 ZoneId.systemDefault()，否则本地开发与生产会差 8 小时且不报错。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_attendance")
public class StudioAttendance implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 签到用户 ID（sys_user.id）：只有绑定账号的在队成员会产生记录，成员资格由 studio_member 判定 */
    private Long userId;

    /** 签到日期（Asia/Shanghai）：由 checkInAt 派生的冗余列，唯一用途是让按天查询命中索引 */
    private LocalDate attendanceDate;

    /** 签到时刻（Asia/Shanghai）；DDL 是秒级 datetime，看板展示到分钟，秒级精度留作排查 */
    private LocalDateTime checkInAt;

    /**
     * 是否内网签到：0-外网，1-内网（由 ClientIpManager 按配置网段判定）
     * 用 Integer 而非 Boolean：DDL 是 tinyint，且语义可能扩展出不同网段
     */
    private Integer inLan;

    /**
     * 来源 IP（IpUtils.normalize 归一化后；审计用，绝不对外）
     * 取不到真实 IP 时存占位串，而不是让签到失败——记录本身比 IP 准确性更重要
     */
    private String ip;

    /** 客户端 UA（截断到 USER_AGENT_MAX_LENGTH；审计用，绝不对外）：客户端可控，不截断会撑爆入库 */
    private String userAgent;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（datetime，映射 LocalDateTime；不用 timestamp，避免 2038 与时区隐式转换） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：本表无更新路径，实际恒等于 createdAt，字段保留只为与其它表结构统一 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除，非0-已删除）
     * 应用层不提供删除，仅为 DBA 极端误操作保留；@TableLogic 让查询自动带 deleted_at = 0
     */
    @TableLogic
    private Long deletedAt;
}

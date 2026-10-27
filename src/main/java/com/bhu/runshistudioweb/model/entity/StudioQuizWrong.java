package com.bhu.runshistudioweb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 刷题错题本实体（对应 studio_quiz_wrong，本模块唯一有状态的表）
 *
 * 必须落表而不是从作答记录派生：correctStreak、手动消解、「消解后再错要重开」都是状态。
 * 唯一写入口是 QuizWrongServiceImpl#applyOutcome：答错 → 回待复习、wrongCount+1、streak 归零；
 * 答对 → streak+1，达阈值即消解（resolveType=1）；手动标记 → 消解（2）；跳过 → 不调用。
 *
 * 坑：题目停用/删除时本表不动，过滤在查询侧，停用因此可逆；uk_user_question 带 deleted_at，
 * 否则「移出错题本后再答错」无法重新入本。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_quiz_wrong")
public class StudioQuizWrong implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（关联 sys_user.id，NOT NULL） */
    private Long userId;

    /** 题目 ID（关联 studio_quiz_question.id，NOT NULL） */
    private Long questionId;

    /** 状态：0-待复习，1-已消解，见 QuizWrongStatusEnum */
    private Integer status;

    /** 累计答错次数：用 Integer 不用 Long，全局 JSON 把 Long 序列化成字符串，前端会拿到 "3" */
    private Integer wrongCount;

    /** 连续答对次数，达到 studio.quiz.resolve-streak 即自动消解；阈值取 2 是因为四选一蒙对概率 25% */
    private Integer correctStreak;

    /** 最近答错时间（错题列表的排序键：最久没复习的先来） */
    private LocalDateTime lastWrongAt;

    /** 消解时间（重新入本时清空） */
    private LocalDateTime resolvedAt;

    /** 消解方式：1-连对达标（自动），2-手动标记；未消解为 null */
    private Integer resolveType;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除） */
    @TableLogic
    private Long deletedAt;
}

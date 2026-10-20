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
 * 刷题错题本实体（对应 studio_quiz_wrong，<b>本模块唯一有状态的表</b>）
 *
 * author: shaoshing
 *
 * <p><b>为什么它必须是一张表，而不是从作答记录派生</b>：
 * {@link #correctStreak}（连对进度）、手动消解、「消解后再错要重开」都是<b>状态</b>，
 * 派生不出来；用窗口函数现算会让每次查错题本都重算一遍，
 * 且「连对 2 次」这类规则没法用一条 SQL 表达清楚。
 *
 * <p><b>状态机</b>（唯一的写入口是 {@code QuizWrongServiceImpl#applyOutcome}）：
 * <pre>
 * 答错 · 无记录        → 插入 status=0, wrongCount=1, streak=0
 * 答错 · 有记录        → status=0, wrongCount+1, streak=0, resolvedAt=null, resolveType=null
 * 答对 · 待复习        → streak+1；达到阈值 → status=1, resolvedAt=now, resolveType=1
 * 答对 · 无记录/已消解 → 不动作
 * 跳过                 → 完全不调用（「跳过不记错」）
 * 手动标记             → status=1, resolvedAt=now, resolveType=2
 * </pre>
 *
 * <p><b>题目被停用/删除时本表不动</b>：过滤发生在查询侧（{@code inSql} 子查询），
 * 这样停用是可逆的——重新启用后错题条目自动回来。
 * 删除才在同事务里清理条目（题目没了，条目无处可去）。
 *
 * <p>唯一键 {@code uk_user_question(user_id, question_id, deleted_at)} 带 {@code deleted_at}：
 * 全项目铁律，否则「移出错题本后再答错」无法重新入本。
 */
@Data
@TableName("studio_quiz_wrong")
public class StudioQuizWrong implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户 ID（关联 sys_user.id，NOT NULL）
     */
    private Long userId;

    /**
     * 题目 ID（关联 studio_quiz_question.id，NOT NULL）
     */
    private Long questionId;

    /**
     * 状态：0-待复习，1-已消解
     *
     * <p>取值见 {@link com.bhu.runshistudioweb.model.enums.QuizWrongStatusEnum}。
     */
    private Integer status;

    /**
     * 累计答错次数（<b>Integer 不用 Long</b>：全局 JSON 配置把 Long 序列化成字符串，
     * 前端会拿到 {@code "3"} 而不是 {@code 3}，见 HELP.md 第 6 条）
     */
    private Integer wrongCount;

    /**
     * 消解进度：连续答对次数（达到 {@code studio.quiz.resolve-streak} 即自动消解）
     *
     * <p>为什么阈值是 2 而不是 1：四选一蒙对的概率是 25%，
     * 一次答对就消解会让「已消解」这个数字失去意义。
     */
    private Integer correctStreak;

    /**
     * 最近答错时间（错题列表的排序键：最久没复习的先来）
     */
    private LocalDateTime lastWrongAt;

    /**
     * 消解时间（重新入本时清空）
     */
    private LocalDateTime resolvedAt;

    /**
     * 消解方式：1-连对达标（自动），2-手动标记；未消解为 null
     */
    private Integer resolveType;

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
     * 创建时间（NOT NULL，无 DB 默认值 → 由 MetaObjectHandler 填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除）
     */
    @TableLogic
    private Long deletedAt;
}

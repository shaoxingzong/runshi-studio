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
 * 刷题作答记录实体（对应 studio_quiz_answer，append-only 审计凭证）
 *
 * 没有修改动作，updated_at 恒等于 created_at，两列只为结构统一保留。
 *
 * 坑：三种 result 语义不同——1 正确 / 2 错误参与统计，3 跳过也是事件（幂等与断点续答要靠它），
 * 但不写错题本、不计入正确率、响应不含答案，否则跳过按钮就成了看答案按钮；
 * chosenLabels 跳过时为 NULL 而非空串，判分用 equals，空串与 NULL 混用会多出一种状态。
 *
 * @author shaoshing
 */
@Data
@TableName("studio_quiz_answer")
public class StudioQuizAnswer implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /** 主键 ID：雪花算法生成（19 位 Long），不走数据库自增 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 作答人 ID（关联 sys_user.id，NOT NULL） */
    private Long userId;

    /**
     * 批次 ID（NOT NULL）：服务端开批时生成，前端整批回传
     * 没有会话表，只承担幂等键与本轮分组；与 uk_user_batch_question 配合保证同批同题只判一次
     */
    private Long batchId;

    /** 题目 ID（关联 studio_quiz_question.id，NOT NULL） */
    private Long questionId;

    /** 用户所选标签（升序无分隔，如 "AC"）；跳过时为 NULL */
    private String chosenLabels;

    /** 提交时刻的正确标签快照（NOT NULL） */
    private String correctLabels;

    /** 结果：1-正确，2-错误，3-跳过，见 QuizAnswerResultEnum；只有 2 会写错题本 */
    private Integer result;

    /** 创建人 ID：插入时由 MyMetaObjectHandler 填充 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /** 更新人 ID：插入与更新时都会填充（本表无更新路径） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /** 发生时间（作答时间 / 跳过时间） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间：本表无更新路径，恒等于 createdAt */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除毫秒时间戳（0-未删除） */
    @TableLogic
    private Long deletedAt;
}

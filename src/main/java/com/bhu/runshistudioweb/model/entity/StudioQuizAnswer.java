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
 * 刷题作答记录实体（对应 studio_quiz_answer，<b>append-only 的审计凭证</b>）
 *
 * author: shaoshing
 *
 * <p><b>本表没有「修改」这个动作</b>：应用层不提供任何 update / delete 入口，
 * 因此每一行的 {@code updated_at} 恒等于 {@code created_at}。
 * 这两个字段仍然按全局约定补齐，只为与其它表结构统一。
 *
 * <p><b>三种 result 的语义差别，是本模块最容易写错的地方</b>：
 * <ul>
 *     <li>{@code 1-正确} / {@code 2-错误}：真正的作答，参与「累计作答」「正确率」统计；</li>
 *     <li>{@code 3-跳过}：也是事件（否则断点续答无法区分「处理过」与「还没轮到」，
 *     幂等也没法做），但它<b>不写错题本</b>、<b>不计入正确率</b>、
 *     响应里<b>不含答案</b>——否则跳过按钮就成了看答案按钮。</li>
 * </ul>
 *
 * <p><b>{@code correctLabels} 是提交时刻的快照</b>，不是外键：
 * 管理员事后改题不会影响这条记录的可解释性（「你当时选了 C，正确答案是 ACD」依然成立）。
 *
 * <p><b>{@code chosenLabels} 在跳过时为 NULL</b>，绝不存空字符串：
 * 本项目全局约定「空值存 NULL」，且判分逻辑用 {@code equals} 比较，
 * 空串与 NULL 混用会让「没选」与「选了空」变成两种状态。
 */
@Data
@TableName("studio_quiz_answer")
public class StudioQuizAnswer implements Serializable {

    /** 序列化版本号：实现 Serializable 后固定写死，避免后续改动导致反序列化失败 */
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID：雪花算法生成（19 位 Long），不走数据库自增
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 作答人 ID（关联 sys_user.id，NOT NULL）
     */
    private Long userId;

    /**
     * 批次 ID（NOT NULL）
     *
     * <p>由服务端开批时用 {@code IdWorker.getId()} 生成，前端在整批的每次提交里回传。
     * 它<b>没有对应的会话表</b>：只承担「幂等键」与「本轮分组」两件事，
     * 题目队列本身由前端持有（题目不含答案，泄露不了）。
     *
     * <p>与 {@code uk_user_batch_question} 配合，保证「同一批里同一题只判一次」。
     */
    private Long batchId;

    /**
     * 题目 ID（关联 studio_quiz_question.id，NOT NULL）
     */
    private Long questionId;

    /**
     * 用户所选标签（升序无分隔，如 {@code "AC"}）；<b>跳过时为 NULL</b>
     */
    private String chosenLabels;

    /**
     * 提交时刻的正确标签<b>快照</b>（NOT NULL）
     */
    private String correctLabels;

    /**
     * 结果：1-正确，2-错误，3-跳过
     *
     * <p>取值见 {@link com.bhu.runshistudioweb.model.enums.QuizAnswerResultEnum}。
     * <b>只有 2（错误）会写错题本</b>——这是「跳过不记错」的唯一实现点。
     */
    private Integer result;

    /**
     * 创建人 ID：插入时由 MyMetaObjectHandler 填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 更新人 ID：插入与更新时都会填充（本表无更新路径）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    /**
     * 发生时间（作答时间 / 跳过时间）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间：本表无更新路径，恒等于 {@code createdAt}
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除毫秒时间戳（0-未删除）
     */
    @TableLogic
    private Long deletedAt;
}
